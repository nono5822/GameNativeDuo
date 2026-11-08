using System;
using System.Collections.Generic;
using System.IO;
using System.Reflection;
using System.Text;
using System.Text.RegularExpressions;
using UnityEngine;

namespace GameNativeDuo.UnitySplit
{
    /// <summary>
    /// This game's profile, %AppData%\GameNativeDuo\profile.json (duo/docs/game-profiles.md): what the adapter's
    /// heuristics would otherwise guess (UI cameras, canvas screens, what stays on top, element names and features) and
    /// companion state read from public members. Data only: no method calls, no writes. Empty when there is no file.
    /// </summary>
    internal sealed class GameProfile
    {
        public static readonly GameProfile Empty = new GameProfile();
        public static GameProfile Current = Empty;

        public static string FilePath => Path.Combine(DuoPaths.Dir, "profile.json");

        private sealed class CameraMatcher
        {
            public Regex Name;
            public string Layer;
            public bool Only;
        }

        public sealed class ElementInfo
        {
            public string Name;

            /// <summary>The features object as JSON, ready for elements.json; null when not given.</summary>
            public string FeaturesJson;
        }

        public sealed class StateEntry
        {
            public string Key, Type, Member;
            internal MemberInfo[] Chain;
            internal bool Failed;
        }

        private readonly List<CameraMatcher> _uiCameras = new List<CameraMatcher>();
        private readonly List<CameraMatcher> _worldCameras = new List<CameraMatcher>();
        private readonly List<KeyValuePair<Regex, DuoScreen>> _canvases = new List<KeyValuePair<Regex, DuoScreen>>();
        private readonly List<Regex> _stayOnTop = new List<Regex>();
        private readonly Dictionary<string, ElementInfo> _elements = new Dictionary<string, ElementInfo>();
        public readonly List<StateEntry> State = new List<StateEntry>();
        public readonly List<string> Builds = new List<string>();
        public string Game = "";

        public bool IsEmpty => _uiCameras.Count == 0 && _worldCameras.Count == 0 && _canvases.Count == 0 && _stayOnTop.Count == 0 &&
            _elements.Count == 0 && State.Count == 0;

        public static GameProfile Load(out string error)
        {
            error = "";
            try
            {
                return File.Exists(FilePath) ? Parse(File.ReadAllText(FilePath)) : Empty;
            }
            catch (Exception ex)
            {
                error = "could not read " + FilePath + ": " + ex.Message;
                return Empty;
            }
        }

        public static GameProfile Parse(string text)
        {
            var p = new GameProfile();
            var root = MiniJson.Parse(text) as Dictionary<string, object>;
            if (root == null)
                return p;
            p.Game = MiniJson.Str(root, "game", "");
            foreach (object b in List(root, "builds"))
            {
                if (b is string)
                    p.Builds.Add((string)b);
            }
            var cams = MiniJson.Obj(root, "cameras");
            Matchers(MiniJson.Obj(root, "cameras") != null ? List(cams, "ui") : null, p._uiCameras);
            Matchers(cams != null ? List(cams, "world") : null, p._worldCameras);
            foreach (object o in List(root, "canvases"))
            {
                var c = o as Dictionary<string, object>;
                string path = MiniJson.Str(c, "path", "");
                DuoScreen s = UiRule.ParseScreen(MiniJson.Str(c, "screen", ""));
                if (path.Length > 0 && s != DuoScreen.Default)
                    p._canvases.Add(new KeyValuePair<Regex, DuoScreen>(Glob(path), s));
            }
            foreach (object o in List(root, "stayOnTop"))
            {
                if (o is string && ((string)o).Length > 0)
                    p._stayOnTop.Add(Glob((string)o));
            }
            var elements = MiniJson.Obj(root, "elements");
            if (elements != null)
            {
                foreach (var pair in elements)
                {
                    var e = pair.Value as Dictionary<string, object>;
                    if (e == null)
                        continue;
                    var f = MiniJson.Obj(e, "features");
                    p._elements[pair.Key] = new ElementInfo { Name = MiniJson.Str(e, "name", null), FeaturesJson = f != null ? MiniJson.Write(f) : null };
                }
            }
            foreach (object o in List(root, "state"))
            {
                var s = o as Dictionary<string, object>;
                var from = MiniJson.Obj(s, "from");
                string key = MiniJson.Str(s, "key", ""), type = MiniJson.Str(from, "type", ""), member = MiniJson.Str(from, "member", "");
                if (key.Length > 0 && type.Length > 0 && member.Length > 0)
                    p.State.Add(new StateEntry { Key = key, Type = type, Member = member });
            }
            return p;
        }

        private static List<object> List(Dictionary<string, object> o, string key)
        {
            object v;
            return o != null && o.TryGetValue(key, out v) && v is List<object> ? (List<object>)v : new List<object>();
        }

        private static void Matchers(List<object> list, List<CameraMatcher> into)
        {
            if (list == null)
                return;
            foreach (object o in list)
            {
                var m = o as Dictionary<string, object>;
                if (m == null)
                    continue;
                string name = MiniJson.Str(m, "name", "");
                into.Add(new CameraMatcher
                {
                    Name = name.Length > 0 ? Glob(name) : null,
                    Layer = MiniJson.Str(m, "layer", null),
                    Only = MiniJson.Bool(m, "only", false),
                });
            }
        }

        /// <summary>`*` = any run but `/`, `**` = any run, case-insensitive, whole string.</summary>
        public static Regex Glob(string glob)
        {
            var sb = new StringBuilder("^");
            for (int i = 0; i < glob.Length; i++)
            {
                char c = glob[i];
                if (c == '*')
                {
                    bool deep = i + 1 < glob.Length && glob[i + 1] == '*';
                    sb.Append(deep ? ".*" : "[^/]*");
                    if (deep)
                        i++;
                }
                else
                {
                    sb.Append(Regex.Escape(c.ToString()));
                }
            }
            return new Regex(sb.Append('$').ToString(), RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
        }

        // ---- what the adapter asks ------------------------------------------------------------------------------------

        /// <summary>True: a game UI camera; false: never one; null: the profile does not say (use the heuristic).</summary>
        public bool? IsUiCamera(string name, int cullingMask)
        {
            foreach (var m in _worldCameras)
            {
                if (Matches(m, name, cullingMask))
                    return false;
            }
            foreach (var m in _uiCameras)
            {
                if (Matches(m, name, cullingMask))
                    return true;
            }
            return null;
        }

        private static bool Matches(CameraMatcher m, string name, int mask)
        {
            if (m.Name != null && !m.Name.IsMatch(name))
                return false;
            if (m.Layer != null)
            {
                int layer = LayerMask.NameToLayer(m.Layer);
                if (layer < 0)
                    return false;
                int bit = 1 << layer;
                if (m.Only ? mask == 0 || (mask & ~bit) != 0 : (mask & bit) == 0)
                    return false;
            }
            return m.Name != null || m.Layer != null;
        }

        /// <summary>The screen the profile gives a canvas (by path), or null.</summary>
        public DuoScreen? CanvasScreen(string path)
        {
            foreach (var c in _canvases)
            {
                if (c.Key.IsMatch(path))
                    return c.Value;
            }
            return null;
        }

        public bool StaysOnTop(string path)
        {
            foreach (Regex r in _stayOnTop)
            {
                if (r.IsMatch(path))
                    return true;
            }
            return false;
        }

        public ElementInfo Element(string id)
        {
            ElementInfo e;
            return id != null && _elements.TryGetValue(id, out e) ? e : null;
        }

        /// <summary>The profile was made for this build (or names none).</summary>
        public bool BuildMatches(string version) => Builds.Count == 0 || Builds.Contains(version);
    }

    /// <summary>
    /// Reads a profile's state entries: `from.type` by name in the game's own assemblies, then `from.member` as a dotted
    /// path of public fields and properties (the first static). Read only; a failing path is logged once and skipped.
    /// </summary>
    internal static class StateReader
    {
        public static bool TryRead(GameProfile.StateEntry e, out object value)
        {
            value = null;
            if (e.Failed)
                return false;
            try
            {
                if (e.Chain == null)
                    e.Chain = Resolve(e);
                object cur = null;
                for (int i = 0; i < e.Chain.Length; i++)
                {
                    MemberInfo m = e.Chain[i];
                    if (i > 0 && cur == null)
                        return false;
                    cur = m is FieldInfo ? ((FieldInfo)m).GetValue(i == 0 ? null : cur) : ((PropertyInfo)m).GetValue(i == 0 ? null : cur, null);
                    if (cur is UnityEngine.Object && (UnityEngine.Object)cur == null)
                        return false;
                }
                value = Plain(cur);
                return value != null;
            }
            catch (Exception ex)
            {
                e.Failed = true;
                Plugin.Log.LogWarning("Profile state " + e.Key + " (" + e.Type + "." + e.Member + ") not readable: " + (ex.InnerException ?? ex).Message);
                return false;
            }
        }

        private static MemberInfo[] Resolve(GameProfile.StateEntry e)
        {
            Type type = GameType(e.Type) ?? throw new Exception("no type " + e.Type + " in the game's assemblies");
            string[] names = e.Member.Split('.');
            var chain = new MemberInfo[names.Length];
            Type cur = type;
            for (int i = 0; i < names.Length; i++)
            {
                BindingFlags flags = BindingFlags.Public | BindingFlags.FlattenHierarchy | (i == 0 ? BindingFlags.Static : BindingFlags.Instance);
                MemberInfo m = (MemberInfo)cur.GetField(names[i], flags) ?? cur.GetProperty(names[i], flags);
                if (m == null)
                    throw new Exception("no public " + (i == 0 ? "static " : "") + "field or property " + names[i] + " on " + cur.Name);
                if (m is PropertyInfo && (((PropertyInfo)m).GetGetMethod() == null || ((PropertyInfo)m).GetIndexParameters().Length > 0))
                    throw new Exception(names[i] + " has no plain public getter");
                chain[i] = m;
                cur = m is FieldInfo ? ((FieldInfo)m).FieldType : ((PropertyInfo)m).PropertyType;
            }
            return chain;
        }

        /// <summary>A type from the game's own assemblies only (never the runtime's, Unity's, BepInEx's or ours).</summary>
        private static Type GameType(string name)
        {
            foreach (Assembly a in AppDomain.CurrentDomain.GetAssemblies())
            {
                string n = a.GetName().Name;
                if (n == "mscorlib" || n == "netstandard" || n.StartsWith("System") || n.StartsWith("Mono.") || n.StartsWith("UnityEngine") ||
                    n.StartsWith("Unity.") || n.StartsWith("BepInEx") || n == "0Harmony" || n.StartsWith("GameNativeDuo") || n.StartsWith("MonoMod"))
                    continue;
                Type t = a.GetType(name, false);
                if (t != null)
                    return t;
            }
            return null;
        }

        /// <summary>Numbers, booleans, strings and enums (as their name) pass; anything else is not state.</summary>
        private static object Plain(object v)
        {
            if (v == null || v is string || v is bool)
                return v;
            if (v is Enum)
                return v.ToString();
            if (v is int || v is long || v is short || v is byte || v is uint || v is float || v is double)
                return Convert.ToDouble(v, System.Globalization.CultureInfo.InvariantCulture);
            return null;
        }
    }
}
