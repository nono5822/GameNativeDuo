using System.Text;
using UnityEngine;

namespace GameNativeDuo.HollowKnight
{
    /// <summary>Debug: writes the cameras and an object tree to the log (layers, renderers, sorting).</summary>
    internal static class Dump
    {
        public static void Cameras()
        {
            var sb = new StringBuilder("Cameras:\n");
            foreach (Camera c in Camera.allCameras)
                sb.AppendFormat("  {0} depth {1} mask 0x{2:x} ortho {3} size {4} rect {5} pos {6} clear {7}\n",
                    Path(c.transform), c.depth, c.cullingMask, c.orthographic, c.orthographicSize, c.rect, c.transform.position, c.clearFlags);
            Plugin.Log.LogInfo(sb.ToString());
        }

        public static void Tree(Transform root, int maxDepth)
        {
            if (root == null)
                return;
            var sb = new StringBuilder("Tree " + Path(root) + ":\n");
            Walk(root, 0, maxDepth, sb);
            Plugin.Log.LogInfo(sb.ToString());
        }

        private static void Walk(Transform t, int depth, int maxDepth, StringBuilder sb)
        {
            var r = t.GetComponent<Renderer>();
            sb.Append(' ', depth * 2).Append(t.name)
                .Append(t.gameObject.activeSelf ? "" : " [off]")
                .Append(" L").Append(LayerMask.LayerToName(t.gameObject.layer)).Append('(').Append(t.gameObject.layer).Append(')')
                .Append(" lp ").Append(t.localPosition.ToString("F2")).Append(" ls ").Append(t.localScale.ToString("F2"));
            if (r != null)
                sb.Append(" R:").Append(r.GetType().Name).Append(" sort ").Append(r.sortingLayerName).Append('/').Append(r.sortingOrder);
            foreach (var mb in t.GetComponents<MonoBehaviour>())
            {
                if (mb != null)
                    sb.Append(" +").Append(mb.GetType().Name);
            }
            sb.Append('\n');
            if (depth >= maxDepth)
                return;
            for (int i = 0; i < t.childCount; i++)
                Walk(t.GetChild(i), depth + 1, maxDepth, sb);
        }

        public static string Path(Transform t)
        {
            string p = t.name;
            while (t.parent != null)
            {
                t = t.parent;
                p = t.name + "/" + p;
            }
            return p;
        }
    }
}
