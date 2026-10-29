using System;
using System.Collections.Generic;
using System.Globalization;
using System.Text;

namespace GameNativeDuo.UnitySplit
{
    /// <summary>
    /// Just enough JSON for GameNative Duo's small files (old Unity Mono runtimes have no JSON library). Objects become
    /// <c>Dictionary&lt;string, object&gt;</c>, arrays <c>List&lt;object&gt;</c>, numbers <c>double</c>.
    /// </summary>
    internal static class MiniJson
    {
        public static object Parse(string text)
        {
            int i = 0;
            object value = Value(text, ref i);
            return value;
        }

        public static Dictionary<string, object> Obj(object o, string key)
        {
            var d = o as Dictionary<string, object>;
            object v;
            return d != null && d.TryGetValue(key, out v) ? v as Dictionary<string, object> : null;
        }

        public static double Num(Dictionary<string, object> o, string key, double fallback)
        {
            object v;
            return o != null && o.TryGetValue(key, out v) && v is double ? (double)v : fallback;
        }

        public static string Str(Dictionary<string, object> o, string key, string fallback)
        {
            object v;
            return o != null && o.TryGetValue(key, out v) && v is string ? (string)v : fallback;
        }

        /// <summary>Appends <paramref name="s"/> as a JSON string literal.</summary>
        public static void Quote(StringBuilder sb, string s)
        {
            sb.Append('"');
            foreach (char c in s)
            {
                switch (c)
                {
                    case '"': sb.Append("\\\""); break;
                    case '\\': sb.Append("\\\\"); break;
                    case '\n': sb.Append("\\n"); break;
                    case '\r': sb.Append("\\r"); break;
                    case '\t': sb.Append("\\t"); break;
                    default:
                        if (c < 0x20)
                            sb.Append("\\u").Append(((int)c).ToString("x4"));
                        else
                            sb.Append(c);
                        break;
                }
            }
            sb.Append('"');
        }

        private static object Value(string s, ref int i)
        {
            Skip(s, ref i);
            if (i >= s.Length)
                throw new FormatException("unexpected end");
            char c = s[i];
            if (c == '{')
                return Object(s, ref i);
            if (c == '[')
                return Array(s, ref i);
            if (c == '"')
                return String(s, ref i);
            if (Word(s, ref i, "true"))
                return true;
            if (Word(s, ref i, "false"))
                return false;
            if (Word(s, ref i, "null"))
                return null;
            int start = i;
            while (i < s.Length && "+-0123456789.eE".IndexOf(s[i]) >= 0)
                i++;
            if (start == i)
                throw new FormatException("unexpected '" + c + "' at " + i);
            return double.Parse(s.Substring(start, i - start), NumberStyles.Float, CultureInfo.InvariantCulture);
        }

        private static Dictionary<string, object> Object(string s, ref int i)
        {
            var result = new Dictionary<string, object>();
            i++; // {
            Skip(s, ref i);
            if (i < s.Length && s[i] == '}')
            {
                i++;
                return result;
            }
            while (true)
            {
                Skip(s, ref i);
                string key = String(s, ref i);
                Skip(s, ref i);
                Expect(s, ref i, ':');
                result[key] = Value(s, ref i);
                Skip(s, ref i);
                if (i < s.Length && s[i] == ',')
                {
                    i++;
                    continue;
                }
                Expect(s, ref i, '}');
                return result;
            }
        }

        private static List<object> Array(string s, ref int i)
        {
            var result = new List<object>();
            i++; // [
            Skip(s, ref i);
            if (i < s.Length && s[i] == ']')
            {
                i++;
                return result;
            }
            while (true)
            {
                result.Add(Value(s, ref i));
                Skip(s, ref i);
                if (i < s.Length && s[i] == ',')
                {
                    i++;
                    continue;
                }
                Expect(s, ref i, ']');
                return result;
            }
        }

        private static string String(string s, ref int i)
        {
            Expect(s, ref i, '"');
            var sb = new StringBuilder();
            while (i < s.Length)
            {
                char c = s[i++];
                if (c == '"')
                    return sb.ToString();
                if (c != '\\')
                {
                    sb.Append(c);
                    continue;
                }
                char e = s[i++];
                switch (e)
                {
                    case 'n': sb.Append('\n'); break;
                    case 'r': sb.Append('\r'); break;
                    case 't': sb.Append('\t'); break;
                    case 'b': sb.Append('\b'); break;
                    case 'f': sb.Append('\f'); break;
                    case 'u':
                        sb.Append((char)int.Parse(s.Substring(i, 4), NumberStyles.HexNumber, CultureInfo.InvariantCulture));
                        i += 4;
                        break;
                    default: sb.Append(e); break;
                }
            }
            throw new FormatException("unterminated string");
        }

        private static bool Word(string s, ref int i, string word)
        {
            if (string.CompareOrdinal(s, i, word, 0, word.Length) != 0)
                return false;
            i += word.Length;
            return true;
        }

        private static void Skip(string s, ref int i)
        {
            while (i < s.Length && char.IsWhiteSpace(s[i]))
                i++;
        }

        private static void Expect(string s, ref int i, char c)
        {
            if (i >= s.Length || s[i] != c)
                throw new FormatException("expected '" + c + "' at " + i);
            i++;
        }
    }
}
