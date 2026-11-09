using System.Collections.Immutable;
using System.Reflection.Metadata;
using System.Reflection.PortableExecutable;
using GameNativeDuo.Tool;
using Xunit;

/// <summary>`duo stubs`: nothing of the original code or embedded data survives; the metadata does.</summary>
public class StubsTests
{
    /// <summary>duo.dll itself: plenty of methods, and embedded resources (the page, the templates).</summary>
    private static readonly byte[] Original = File.ReadAllBytes(typeof(Stubs).Assembly.Location);

    [Fact]
    public void EveryBodyThrowsAndResourcesAreZero()
    {
        var r = Stubs.Strip(Original)!;
        Assert.True(r.Methods > 100, "methods " + r.Methods);
        Assert.True(r.Resources > 0, "resources " + r.Resources);

        using var pe = new PEReader(ImmutableArray.Create(r.Image));
        var md = pe.GetMetadataReader();
        foreach (var h in md.MethodDefinitions)
        {
            int rva = md.GetMethodDefinition(h).RelativeVirtualAddress;
            if (rva == 0)
                continue;
            byte[] il = pe.GetMethodBody(rva).GetILBytes()!;
            Assert.True(il is [0x2A] or [0x14, 0x7A], $"{md.GetString(md.GetMethodDefinition(h).Name)}: {Convert.ToHexString(il)}");
        }
        var res = pe.GetSectionData(pe.PEHeaders.CorHeader!.ResourcesDirectory.RelativeVirtualAddress);
        foreach (var h in md.ManifestResources)
        {
            var reader = res.GetReader((int)md.GetManifestResource(h).Offset, res.Length - (int)md.GetManifestResource(h).Offset);
            int length = reader.ReadInt32();
            Assert.True(reader.ReadBytes(length).All(b => b == 0));
        }
        // Names and signatures stay: what a compiler needs.
        Assert.Equal(
            new PEReader(ImmutableArray.Create(Original)).GetMetadataReader().TypeDefinitions.Count,
            md.TypeDefinitions.Count);
    }

    [Fact]
    public void NotManagedIsNull() => Assert.Null(Stubs.Strip(new byte[] { 0x7F, (byte)'E', (byte)'L', (byte)'F', 0, 0, 0, 0 }.Concat(new byte[200]).ToArray()));
}
