using System;
using System.Collections.Generic;
using System.Globalization;
using System.Text;

namespace GameNativeDuo.Sdk
{
    /// <summary>
    /// What the editors may do to an element (uniform-editing.md): the screens they offer, whether it moves, the scale
    /// range. The defaults allow everything; declare only what differs.
    /// </summary>
#if DUO_SDK_EMBEDDED
    internal
#else
    public
#endif
    sealed class ElementFeatures
    {
        public static readonly ElementFeatures All = new ElementFeatures();

        /// <summary>The screens the editors offer (hidden = turned off); null for all.</summary>
        public DuoScreen[] Screens;

        public bool Move = true;

        /// <summary>False: the element ignores scale. Otherwise <see cref="ScaleMin"/>..<see cref="ScaleMax"/>.</summary>
        public bool Scale = true;

        public float ScaleMin = 0.25f, ScaleMax = 4f;

        public bool IsAll => Screens == null && Move && Scale && ScaleMin <= 0.25f && ScaleMax >= 4f;

        /// <summary>The JSON object for elements.json and features.json, or null when everything is allowed.</summary>
        public void Write(StringBuilder sb)
        {
            sb.Append('{');
            bool first = true;
            if (Screens != null)
            {
                sb.Append("\"screens\":[");
                for (int i = 0; i < Screens.Length; i++)
                {
                    if (i > 0)
                        sb.Append(',');
                    MiniJson.Quote(sb, UiRule.ScreenId(Screens[i]));
                }
                sb.Append(']');
                first = false;
            }
            if (!Move)
            {
                sb.Append(first ? "" : ",").Append("\"move\":false");
                first = false;
            }
            if (!Scale)
                sb.Append(first ? "" : ",").Append("\"scale\":false");
            else if (ScaleMin > 0.25f || ScaleMax < 4f)
                sb.Append(first ? "" : ",").Append("\"scale\":{\"min\":").Append(F(ScaleMin)).Append(",\"max\":").Append(F(ScaleMax)).Append('}');
            sb.Append('}');
        }

        internal static string F(float v) => v.ToString("0.###", CultureInfo.InvariantCulture);
    }

    /// <summary>One setting of a plugin element: values as (id, label), the first is the default.</summary>
#if DUO_SDK_EMBEDDED
    internal
#else
    public
#endif
    sealed class ElementOption
    {
        public string Id, Label;
        public string[][] Values;

        public ElementOption(string id, string label, params string[][] values)
        {
            Id = id;
            Label = label;
            Values = values;
        }
    }

    /// <summary>One entry of elements.json: where an element is now (rules applied), in window pixels.</summary>
#if DUO_SDK_EMBEDDED
    internal
#else
    public
#endif
    sealed class ElementInfo
    {
        public string Id, Name;

        /// <summary>Index of the parent in the same list, -1 for a root. Parents come before children.</summary>
        public int Parent = -1;

        public DuoScreen Screen = DuoScreen.Bottom;
        public DuoRect Rect;

        /// <summary>It draws something (the editors outline it).</summary>
        public bool Graphic = true;

        /// <summary>False when it is not on screen now; <see cref="When"/> says when it shows ("During a run").</summary>
        public bool Present = true;

        public string When;
        public ElementFeatures Features;
        public ElementOption[] Options;
    }

    /// <summary>Writes elements.json and features.json in the contract's format.</summary>
#if DUO_SDK_EMBEDDED
    internal
#else
    public
#endif
    static class ElementsFile
    {
        public static string Build(string engine, int screenW, int screenH, IList<ElementInfo> elements)
        {
            var sb = new StringBuilder(256 + elements.Count * 160);
            sb.Append("{\"engine\":");
            MiniJson.Quote(sb, engine);
            sb.Append(",\"screen\":{\"w\":").Append(screenW).Append(",\"h\":").Append(screenH).Append("},\"elements\":[");
            for (int i = 0; i < elements.Count; i++)
            {
                if (i > 0)
                    sb.Append(",\n");
                Element(sb, elements[i], true);
            }
            sb.Append("]}");
            return sb.ToString();
        }

        /// <summary>
        /// features.json: what the mod supports before the game runs (touch, background modes, its fixed elements);
        /// <paramref name="dynamic"/> when more elements come from the running game.
        /// </summary>
        public static string BuildFeatures(string engine, bool touch, string[] backgrounds, IList<ElementInfo> fixedElements, bool dynamic)
        {
            var sb = new StringBuilder(256);
            sb.Append("{\"format\":\"gamenative-duo/features\",\"version\":1,\"engine\":");
            MiniJson.Quote(sb, engine);
            sb.Append(",\"touch\":").Append(touch ? "true" : "false").Append(",\"background\":[");
            for (int i = 0; i < backgrounds.Length; i++)
            {
                if (i > 0)
                    sb.Append(',');
                MiniJson.Quote(sb, backgrounds[i]);
            }
            sb.Append("],\"elements\":[");
            for (int i = 0; i < fixedElements.Count; i++)
            {
                if (i > 0)
                    sb.Append(',');
                var e = fixedElements[i];
                sb.Append("{\"id\":");
                MiniJson.Quote(sb, e.Id);
                sb.Append(",\"name\":");
                MiniJson.Quote(sb, e.Name ?? e.Id);
                if (!string.IsNullOrEmpty(e.When))
                {
                    sb.Append(",\"when\":");
                    MiniJson.Quote(sb, e.When);
                }
                if (e.Features != null && !e.Features.IsAll)
                {
                    sb.Append(",\"features\":");
                    e.Features.Write(sb);
                }
                sb.Append('}');
            }
            sb.Append("],\"dynamic\":").Append(dynamic ? "true" : "false").Append('}');
            return sb.ToString();
        }

        private static void Element(StringBuilder sb, ElementInfo e, bool withRect)
        {
            sb.Append("{\"id\":");
            MiniJson.Quote(sb, e.Id);
            sb.Append(",\"name\":");
            MiniJson.Quote(sb, e.Name ?? e.Id);
            sb.Append(",\"parent\":").Append(e.Parent);
            sb.Append(",\"screen\":");
            MiniJson.Quote(sb, e.Screen == DuoScreen.Default ? "bottom" : UiRule.ScreenId(e.Screen));
            if (withRect)
                sb.Append(",\"x\":").Append(e.Rect.X).Append(",\"y\":").Append(e.Rect.Y).Append(",\"w\":").Append(e.Rect.W).Append(",\"h\":").Append(e.Rect.H);
            sb.Append(",\"graphic\":").Append(e.Graphic ? "true" : "false");
            if (!e.Present)
                sb.Append(",\"present\":false");
            if (!string.IsNullOrEmpty(e.When))
            {
                sb.Append(",\"when\":");
                MiniJson.Quote(sb, e.When);
            }
            if (e.Features != null && !e.Features.IsAll)
            {
                sb.Append(",\"features\":");
                e.Features.Write(sb);
            }
            if (e.Options != null && e.Options.Length > 0)
            {
                sb.Append(",\"options\":[");
                for (int i = 0; i < e.Options.Length; i++)
                {
                    var o = e.Options[i];
                    if (i > 0)
                        sb.Append(',');
                    sb.Append("{\"id\":");
                    MiniJson.Quote(sb, o.Id);
                    sb.Append(",\"label\":");
                    MiniJson.Quote(sb, o.Label);
                    sb.Append(",\"values\":[");
                    for (int v = 0; v < o.Values.Length; v++)
                    {
                        if (v > 0)
                            sb.Append(',');
                        sb.Append('[');
                        MiniJson.Quote(sb, o.Values[v][0]);
                        sb.Append(',');
                        MiniJson.Quote(sb, o.Values[v].Length > 1 ? o.Values[v][1] : o.Values[v][0]);
                        sb.Append(']');
                    }
                    sb.Append("]}");
                }
                sb.Append(']');
            }
            sb.Append('}');
        }
    }
}
