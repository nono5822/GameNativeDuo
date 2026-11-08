using System.Collections.Immutable;
using System.IO.Compression;
using System.Reflection.Metadata;
using System.Reflection.Metadata.Ecma335;
using System.Reflection.PortableExecutable;
using System.Security.Cryptography;

namespace GameNativeDuo.Tool;

/// <summary>
/// `duo stubs DIR --out SET.zip [--only A.dll,B.dll]`: reference stubs of a game's managed assemblies, for building mods
/// from source in CI without game files (devkit-platform.md, "Code trust"). Every method body becomes `throw null`,
/// embedded resources and static data blobs are zeroed; names and signatures stay (private ones too: mods built
/// against publicized assemblies or patching private methods with Harmony need them to compile). A stub compiles
/// against, but runs nothing and carries none of the game's code or assets. The zip is deterministic: the same input
/// gives the same bytes, so refs.json can pin it by sha256.
/// </summary>
public static class Stubs
{
    /// <summary>Tiny method header for a 2-byte body (`(2 << 2) | 2`), then `ldnull`, `throw`.</summary>
    private static readonly byte[] ThrowNull = { 0x0A, 0x14, 0x7A };

    private static readonly DateTimeOffset Epoch = new(2000, 1, 1, 0, 0, 0, TimeSpan.Zero);

    public sealed record Result(byte[] Image, int Methods, int Resources, int DataBlobs);

    public static int Run(Args a)
    {
        string dir = a.Positional.FirstOrDefault() ?? throw new DuoException("usage: duo stubs DIR --out SET.zip [--only A.dll,B.dll]");
        string output = a["out"] ?? throw new DuoException("--out SET.zip is required");
        var only = a["only"]?.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .ToHashSet(StringComparer.OrdinalIgnoreCase);
        if (!Directory.Exists(dir))
            throw new DuoException($"{dir}: no such folder");
        var entries = new SortedDictionary<string, byte[]>(StringComparer.Ordinal);
        foreach (string path in Directory.GetFiles(dir, "*.dll").OrderBy(p => p, StringComparer.Ordinal))
        {
            string name = Path.GetFileName(path);
            if (only != null && !only.Contains(name))
                continue;
            var r = Strip(File.ReadAllBytes(path));
            if (r == null)
            {
                Console.WriteLine($"  {name}: not a managed assembly, left out");
                continue;
            }
            entries[name] = r.Image;
            Console.WriteLine($"  {name}: {r.Methods} method bodies, {r.Resources} resources, {r.DataBlobs} data blobs emptied");
        }
        if (only != null)
            foreach (string missing in only.Where(n => !entries.ContainsKey(n)))
                throw new DuoException($"{missing}: not found in {dir}");
        if (entries.Count == 0)
            throw new DuoException("no managed assemblies to stub");
        WriteZip(output, entries);
        Console.WriteLine($"{output}: {entries.Count} stub(s), sha256 {Convert.ToHexStringLower(SHA256.HashData(File.ReadAllBytes(output)))}");
        return 0;
    }

    /// <summary>The stubbed image, or null when <paramref name="image"/> is not a managed assembly.</summary>
    public static Result? Strip(byte[] image)
    {
        try
        {
            return StripManaged(image);
        }
        catch (BadImageFormatException)
        {
            return null;
        }
    }

    private static Result? StripManaged(byte[] image)
    {
        using var pe = new PEReader(ImmutableArray.Create(image));
        if (!pe.HasMetadata)
            return null;
        var md = pe.GetMetadataReader();
        var headers = pe.PEHeaders;
        byte[] copy = (byte[])image.Clone();
        int methods = 0, resources = 0, blobs = 0;

        foreach (var handle in md.MethodDefinitions)
        {
            int rva = md.GetMethodDefinition(handle).RelativeVirtualAddress;
            if (rva == 0 || !FileOffset(headers, rva, out int at))
                continue;
            int size = pe.GetMethodBody(rva).Size;
            if (size <= 2)
                continue; // a bare `ret`: nothing to hide
            ThrowNull.CopyTo(copy, at);
            Array.Clear(copy, at + ThrowNull.Length, size - ThrowNull.Length);
            methods++;
        }

        var resourcesDir = headers.CorHeader!.ResourcesDirectory;
        if (resourcesDir.Size > 0 && FileOffset(headers, resourcesDir.RelativeVirtualAddress, out int start))
        {
            foreach (var handle in md.ManifestResources)
            {
                var res = md.GetManifestResource(handle);
                if (!res.Implementation.IsNil)
                    continue; // lives in another file
                int at = start + (int)res.Offset;
                int length = BitConverter.ToInt32(copy, at);
                Array.Clear(copy, at + 4, length);
                resources++;
            }
        }

        foreach (var handle in md.FieldDefinitions)
        {
            var field = md.GetFieldDefinition(handle);
            int rva = field.GetRelativeVirtualAddress();
            if (rva == 0 || !FileOffset(headers, rva, out int at))
                continue;
            int size = DataSize(md, field);
            if (size <= 0 || at + size > copy.Length)
                continue;
            Array.Clear(copy, at, size);
            blobs++;
        }
        return new Result(copy, methods, resources, blobs);
    }

    /// <summary>Bytes of a field's static data (array initializers: a value type with an explicit size).</summary>
    private static int DataSize(MetadataReader md, FieldDefinition field)
    {
        var blob = md.GetBlobReader(field.Signature);
        if (blob.ReadSignatureHeader().Kind != SignatureKind.Field)
            return 0;
        // The raw element type (ECMA-335 II.23.1.16): ReadSignatureTypeCode folds CLASS and VALUETYPE together.
        switch (blob.ReadByte())
        {
            case 0x02 or 0x04 or 0x05: return 1; // bool, int8, uint8
            case 0x03 or 0x06 or 0x07: return 2; // char, int16, uint16
            case 0x08 or 0x09 or 0x0C: return 4; // int32, uint32, float32
            case 0x0A or 0x0B or 0x0D: return 8; // int64, uint64, float64
            case 0x11: // VALUETYPE
                var type = blob.ReadTypeHandle();
                return type.Kind == HandleKind.TypeDefinition ? md.GetTypeDefinition((TypeDefinitionHandle)type).GetLayout().Size : 0;
            default: return 0;
        }
    }

    /// <summary>Where relative virtual address <paramref name="rva"/> sits in the file, through the section table.</summary>
    private static bool FileOffset(PEHeaders headers, int rva, out int offset)
    {
        foreach (var s in headers.SectionHeaders)
        {
            if (rva >= s.VirtualAddress && rva < s.VirtualAddress + Math.Max(s.VirtualSize, s.SizeOfRawData))
            {
                offset = rva - s.VirtualAddress + s.PointerToRawData;
                return true;
            }
        }
        offset = 0;
        return false;
    }

    private static void WriteZip(string path, SortedDictionary<string, byte[]> entries)
    {
        if (Path.GetDirectoryName(Path.GetFullPath(path)) is { } dir)
            Directory.CreateDirectory(dir);
        using var file = File.Create(path);
        using var zip = new ZipArchive(file, ZipArchiveMode.Create);
        foreach (var (name, bytes) in entries)
        {
            var entry = zip.CreateEntry(name, CompressionLevel.SmallestSize);
            entry.LastWriteTime = Epoch;
            using var s = entry.Open();
            s.Write(bytes);
        }
    }
}
