using System;
using System.Collections.Generic;
using System.Linq;
using System.Reflection;
using System.Text;
using HarmonyLib;
using HutongGames.PlayMaker;
using UnityEngine;

namespace GameNativeDuo.HollowKnight
{
    /// <summary>
    /// Test-mode only (<see cref="TestHarness"/>): finds every path that opens the game's own map, so the live map can
    /// keep the map button to itself.
    /// </summary>
    internal static class Diagnostics
    {
        /// <summary>Logs each call into GameMap's methods (who opens, sets up and closes the game's map) with its caller.</summary>
        public static void TraceMapCalls(Harmony harmony)
        {
            var prefix = new HarmonyMethod(typeof(Diagnostics), nameof(LogCall));
            var everyFrame = new HashSet<string> { "Update", "LateUpdate", "FixedUpdate", "PositionCompass", "GetMapBounds" };
            foreach (MethodInfo m in typeof(GameMap).GetMethods(BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.DeclaredOnly))
            {
                if (m.IsAbstract || m.ContainsGenericParameters || m.IsSpecialName || everyFrame.Contains(m.Name) || m.GetMethodBody() == null)
                    continue;
                try
                {
                    harmony.Patch(m, prefix: prefix);
                }
                catch (Exception ex)
                {
                    Plugin.Log.LogWarning("Trace patch failed for GameMap." + m.Name + ": " + ex.Message);
                }
            }
        }

        private static void LogCall(MethodBase __originalMethod)
        {
            try
            {
                var who = new StringBuilder();
                // PlayMaker calls through reflection: name the FSM state that is running, if any.
                Fsm fsm = FsmExecutionStack.ExecutingFsm;
                if (fsm != null)
                    who.Append(" fsm '").Append(fsm.Name).Append("' on ").Append(fsm.GameObject != null ? Path(fsm.GameObject.transform) : "?")
                        .Append(" state '").Append(fsm.ActiveStateName).Append("'");
                string[] frames = Environment.StackTrace.Split('\n')
                    .Select(l => l.Trim())
                    .Where(l => l.StartsWith("at ") && !l.Contains("Diagnostics") && !l.Contains("DMD<") && !l.Contains("StackTrace"))
                    .Take(3)
                    .Select(l => l.Substring(3, Math.Max(0, (l.IndexOf(" [0x") > 0 ? l.IndexOf(" [0x") : l.Length) - 3)))
                    .ToArray();
                Plugin.Log.LogInfo("TRACE GameMap." + __originalMethod.Name + " frame " + Time.frameCount + who +
                    ", live map shown " + (LiveMap.Instance != null && LiveMap.Instance.Shown) + ", owns button " + LiveMap.OwnsMapButton +
                    " <- " + string.Join(" <- ", frames));
            }
            catch (Exception ex)
            {
                Plugin.Log.LogWarning("Trace failed: " + ex.Message);
            }
        }

        /// <summary>Every method in the game's code whose IL touches a member named <paramref name="name"/>.</summary>
        public static void Refs(string name)
        {
            int found = 0;
            foreach (Assembly asm in new[] { typeof(GameMap).Assembly, typeof(Fsm).Assembly })
            {
                Type[] types;
                try
                {
                    types = asm.GetTypes();
                }
                catch (ReflectionTypeLoadException ex)
                {
                    types = ex.Types.Where(t => t != null).ToArray();
                }
                foreach (Type t in types)
                {
                    foreach (MethodBase m in t.GetMethods(BindingFlags.Instance | BindingFlags.Static | BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.DeclaredOnly)
                        .Cast<MethodBase>().Concat(t.GetConstructors(BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic)))
                    {
                        if (m.IsAbstract || m.GetMethodBody() == null)
                            continue;
                        try
                        {
                            foreach (KeyValuePair<System.Reflection.Emit.OpCode, object> ins in PatchProcessor.ReadMethodBody(m))
                            {
                                var member = ins.Value as MemberInfo;
                                string s = member != null ? member.Name : ins.Value as string;
                                if (s == null || s.IndexOf(name, StringComparison.OrdinalIgnoreCase) < 0)
                                    continue;
                                Plugin.Log.LogInfo("  ref " + t.FullName + "." + m.Name + " -> " + ins.Key + " " + (member != null ? member.DeclaringType + "." + member.Name : "\"" + s + "\""));
                                found++;
                                break;
                            }
                        }
                        catch (Exception)
                        {
                            // Some generic or native methods cannot be read: skip them.
                        }
                    }
                }
            }
            Plugin.Log.LogInfo("  refs to '" + name + "': " + found);
        }

        private static readonly Dictionary<string, HashSet<string>> Snaps = new Dictionary<string, HashSet<string>>();

        /// <summary>
        /// What the game's map shows right now: every renderer under Game_Map that is on, in an active object and not
        /// fully transparent (rooms, area and room names, pins, markers, the compass). Kept under a name for <see cref="Diff"/>.
        /// </summary>
        public static void Snap(string name)
        {
            GameManager gm = GameManager.instance;
            if (gm == null || gm.gameMap == null)
                return;
            var set = new HashSet<string>();
            Transform root = gm.gameMap.transform;
            // Only what a camera that draws the map draws: ours while the live map is up, else the HUD camera.
            int drawn = LiveMap.Instance != null && LiveMap.Instance.Shown ? LiveMap.Instance.CullingMask
                : GameCameras.instance != null ? GameCameras.instance.hudCamera.cullingMask : ~0;
            foreach (Renderer r in root.GetComponentsInChildren<Renderer>(false))
            {
                if (!r.enabled || !r.gameObject.activeInHierarchy || (drawn & (1 << r.gameObject.layer)) == 0)
                    continue;
                var sr = r as SpriteRenderer;
                if (sr != null && (sr.sprite == null || sr.color.a < 0.02f))
                    continue;
                if (!TextVisible(r.gameObject))
                    continue;
                set.Add(RelPath(r.transform, root));
            }
            Snaps[name] = set;
            Plugin.Log.LogInfo("  snap '" + name + "': " + set.Count + " visible renderers");
        }

        /// <summary>Renderers visible in one snap and not the other, both ways.</summary>
        public static void Diff(string a, string b)
        {
            HashSet<string> sa, sb;
            if (!Snaps.TryGetValue(a, out sa) || !Snaps.TryGetValue(b, out sb))
            {
                Plugin.Log.LogInfo("  no snap " + a + " or " + b);
                return;
            }
            var onlyA = sa.Where(x => !sb.Contains(x)).OrderBy(x => x).ToArray();
            var onlyB = sb.Where(x => !sa.Contains(x)).OrderBy(x => x).ToArray();
            Plugin.Log.LogInfo("  diff: " + onlyA.Length + " only in '" + a + "', " + onlyB.Length + " only in '" + b + "'" +
                string.Concat(onlyA.Select(x => "\n    only " + a + ": " + x).ToArray()) +
                string.Concat(onlyB.Select(x => "\n    only " + b + ": " + x).ToArray()));
        }

        /// <summary>False for a text component (TextMesh, TextMeshPro) that is empty or transparent; true otherwise.</summary>
        private static bool TextVisible(GameObject go)
        {
            foreach (Component c in go.GetComponents<Component>())
            {
                if (c == null || c.GetType().Name.IndexOf("Text", StringComparison.Ordinal) < 0)
                    continue;
                PropertyInfo text = c.GetType().GetProperty("text");
                PropertyInfo color = c.GetType().GetProperty("color");
                if (text == null || color == null)
                    continue;
                string s = text.GetValue(c, null) as string;
                object col = color.GetValue(c, null);
                if (string.IsNullOrEmpty(s) || (col is Color && ((Color)col).a < 0.02f))
                    return false;
            }
            return true;
        }

        private static string RelPath(Transform t, Transform root)
        {
            string p = t.name;
            for (Transform x = t.parent; x != null && x != root; x = x.parent)
                p = x.name + "/" + p;
            return p;
        }

        /// <summary>PlayerData fields by name prefix, or one field set (bool / int, in memory only: test mode never saves).</summary>
        public static void PlayerDataCmd(string[] a)
        {
            PlayerData pd = PlayerData.instance;
            if (pd == null || a.Length < 2)
                return;
            if (a.Length == 2)
            {
                var sb = new StringBuilder("  playerdata " + a[1] + "*:");
                foreach (FieldInfo f in typeof(PlayerData).GetFields(BindingFlags.Instance | BindingFlags.Public))
                {
                    if (f.Name.StartsWith(a[1], StringComparison.OrdinalIgnoreCase) && (f.FieldType == typeof(bool) || f.FieldType == typeof(int)))
                        sb.Append(' ').Append(f.Name).Append('=').Append(f.GetValue(pd));
                }
                Plugin.Log.LogInfo(sb.ToString());
                return;
            }
            FieldInfo field = typeof(PlayerData).GetField(a[1], BindingFlags.Instance | BindingFlags.Public);
            if (field == null)
            {
                Plugin.Log.LogInfo("  no playerdata field " + a[1]);
                return;
            }
            if (field.FieldType == typeof(bool))
                field.SetValue(pd, a[2] == "true" || a[2] == "1");
            else if (field.FieldType == typeof(int))
                field.SetValue(pd, int.Parse(a[2]));
            Plugin.Log.LogInfo("  playerdata " + a[1] + " = " + field.GetValue(pd));
        }

        /// <summary>
        /// A smaller kingdom, in memory: only the named areas' maps (GameMap area objects, e.g. "Crossroads Dirtmouth")
        /// stay bought and only their rooms stay mapped. Shows how both maps treat a player who has seen less.
        /// </summary>
        public static void KeepAreas(string[] areaNames)
        {
            PlayerData pd = PlayerData.instance;
            GameManager gm = GameManager.instance;
            if (pd == null || gm == null || gm.gameMap == null)
                return;
            var keepRooms = new HashSet<string>();
            foreach (Transform area in gm.gameMap.transform)
            {
                if (!areaNames.Any(n => area.name.IndexOf(n, StringComparison.OrdinalIgnoreCase) >= 0))
                    continue;
                foreach (Transform room in area)
                    keepRooms.Add(room.name);
            }
            int before = pd.scenesMapped.Count;
            pd.scenesMapped.RemoveAll(s => !keepRooms.Contains(s));
            var sb = new StringBuilder();
            foreach (FieldInfo f in typeof(PlayerData).GetFields(BindingFlags.Instance | BindingFlags.Public))
            {
                if (f.FieldType != typeof(bool) || !f.Name.StartsWith("map") || f.Name == "mapAllRooms" || f.Name == "mapKeyPref")
                    continue;
                bool keep = areaNames.Any(n => f.Name.IndexOf(n, StringComparison.OrdinalIgnoreCase) >= 0);
                if ((bool)f.GetValue(pd) && !keep)
                {
                    f.SetValue(pd, false);
                    sb.Append(' ').Append(f.Name);
                }
            }
            Plugin.Log.LogInfo("  kept areas " + string.Join(", ", areaNames) + ": scenesMapped " + before + " -> " + pd.scenesMapped.Count + ", maps off:" + sb);
        }

        /// <summary>A method's IL, one instruction per line (there is no decompiler on the device).</summary>
        public static void Il(string typeName, string methodName)
        {
            Type t = typeof(GameMap).Assembly.GetType(typeName) ?? typeof(Fsm).Assembly.GetType(typeName);
            if (t == null)
            {
                Plugin.Log.LogInfo("  no type " + typeName);
                return;
            }
            foreach (MethodBase m in t.GetMethods(BindingFlags.Instance | BindingFlags.Static | BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.DeclaredOnly))
            {
                if (m.Name != methodName)
                    continue;
                var sb = new StringBuilder("  IL " + t.Name + "." + m.Name + "(" + string.Join(", ", m.GetParameters().Select(p => p.ParameterType.Name).ToArray()) + ")");
                int i = 0;
                foreach (KeyValuePair<System.Reflection.Emit.OpCode, object> ins in PatchProcessor.ReadMethodBody(m))
                {
                    var member = ins.Value as MemberInfo;
                    string operand = member != null ? (member.DeclaringType != null ? member.DeclaringType.Name + "." : "") + member.Name
                        : ins.Value is string ? "\"" + ins.Value + "\"" : ins.Value != null ? ins.Value.ToString() : "";
                    sb.Append("\n    ").Append(i++).Append(' ').Append(ins.Key.Name).Append(' ').Append(operand);
                }
                Plugin.Log.LogInfo(sb.ToString());
            }
        }

        /// <summary>Every PlayMaker FSM (loaded, active or not) with an action whose type, or an event or state, contains <paramref name="what"/>.</summary>
        public static void Fsms(string what)
        {
            int found = 0;
            foreach (PlayMakerFSM pm in Resources.FindObjectsOfTypeAll<PlayMakerFSM>())
            {
                if (pm == null || pm.gameObject.scene.name == null)
                    continue; // prefabs and assets, not in a scene
                Fsm fsm = pm.Fsm;
                var hits = new StringBuilder();
                foreach (FsmState state in fsm.States)
                {
                    foreach (FsmStateAction a in state.Actions)
                    {
                        if (a != null && a.GetType().Name.IndexOf(what, StringComparison.OrdinalIgnoreCase) >= 0)
                            hits.Append("\n      state '").Append(state.Name).Append("' action ").Append(a.GetType().Name).Append(ActionFields(a));
                    }
                    foreach (FsmTransition tr in state.Transitions)
                    {
                        if (tr.EventName != null && tr.EventName.IndexOf(what, StringComparison.OrdinalIgnoreCase) >= 0)
                            hits.Append("\n      state '").Append(state.Name).Append("' --").Append(tr.EventName).Append("--> '").Append(tr.ToState).Append("'");
                    }
                    if (state.Name.IndexOf(what, StringComparison.OrdinalIgnoreCase) >= 0)
                        hits.Append("\n      state '").Append(state.Name).Append("' (").Append(state.Actions.Length).Append(" actions: ")
                            .Append(string.Join(", ", state.Actions.Where(x => x != null).Select(x => x.GetType().Name).ToArray())).Append(")");
                }
                if (hits.Length == 0)
                    continue;
                found++;
                Plugin.Log.LogInfo("  fsm '" + pm.FsmName + "' on " + Path(pm.transform) + " (active " + pm.gameObject.activeInHierarchy + ", enabled " + pm.enabled +
                    ", state '" + pm.ActiveStateName + "')" + hits);
            }
            Plugin.Log.LogInfo("  fsms matching '" + what + "': " + found);
        }

        /// <summary>An action's FsmEvent fields, e.g. " [wasPressed=QUICK MAP]".</summary>
        private static string ActionFields(FsmStateAction a)
        {
            var sb = new StringBuilder();
            foreach (FieldInfo f in a.GetType().GetFields(BindingFlags.Instance | BindingFlags.Public))
            {
                object v = f.GetValue(a);
                if (v is FsmEvent e && e != null)
                    sb.Append(' ').Append(f.Name).Append('=').Append(e.Name);
                else if (v is FsmBool b && b != null)
                    sb.Append(' ').Append(f.Name).Append('=').Append(b.Value);
                else if (v is FsmString s && s != null)
                    sb.Append(' ').Append(f.Name).Append('=').Append(s.Value);
            }
            return sb.Length > 0 ? " [" + sb.ToString().Trim() + "]" : "";
        }

        internal static string Path(Transform t)
        {
            string p = t.name;
            for (Transform x = t.parent; x != null; x = x.parent)
                p = x.name + "/" + p;
            return p;
        }
    }
}
