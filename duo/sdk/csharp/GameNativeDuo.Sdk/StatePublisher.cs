using System;
using System.Collections.Generic;
using System.Text;

namespace GameNativeDuo.Sdk
{
    /// <summary>
    /// Companion state on the mod side (companions.md): <see cref="Publish"/> remembers a value; <see cref="Flush"/>
    /// sends the changed keys the app subscribed to, at most 30 times a second, split into datagrams of at most 8 KB.
    /// When nobody looks (no subscription) nothing is sent and nothing but the dictionary write costs anything. A new
    /// subscription gets a full snapshot of its keys first. Game thread only.
    /// </summary>
#if DUO_SDK_EMBEDDED
    internal
#else
    public
#endif
    sealed class StatePublisher
    {
        private readonly Dictionary<string, object> _values = new Dictionary<string, object>();
        private readonly HashSet<string> _dirty = new HashSet<string>();
        private List<string> _subscription = new List<string>();
        private bool _snapshotDue;
        private DateTime _lastSend = DateTime.MinValue;

        /// <summary>Minimum time between two sends.</summary>
        public static readonly TimeSpan Gap = TimeSpan.FromMilliseconds(33);

        public bool HasSubscribers => _subscription.Count > 0;

        /// <summary>True when the app wants <paramref name="key"/>: compute expensive values only then.</summary>
        public bool Wants(string key)
        {
            foreach (var s in _subscription)
            {
                if (Matches(s, key))
                    return true;
            }
            return false;
        }

        /// <summary>Entry <paramref name="s"/> covers <paramref name="key"/> when they are equal or the key is under it.</summary>
        public static bool Matches(string s, string key) =>
            key == s || (key.Length > s.Length && key.StartsWith(s, StringComparison.Ordinal) && (key[s.Length] == '.' || s.EndsWith(".", StringComparison.Ordinal)));

        public void Publish(string key, object value)
        {
            if (string.IsNullOrEmpty(key))
                return;
            object old;
            if (_values.TryGetValue(key, out old) && Same(old, value))
                return;
            if (value == null)
                _values.Remove(key);
            else
                _values[key] = value;
            _dirty.Add(key);
        }

        /// <summary>A SUBSCRIBE from the app: <c>{"keys": [...]}</c>.</summary>
        public void OnSubscribe(string json)
        {
            var keys = new List<string>();
            try
            {
                var list = (MiniJson.Parse(json) as Dictionary<string, object>)?["keys"] as List<object>;
                if (list != null)
                {
                    foreach (var k in list)
                    {
                        if (k is string)
                            keys.Add((string)k);
                    }
                }
            }
            catch (Exception)
            {
            }
            _subscription = keys;
            _snapshotDue = keys.Count > 0;
        }

        /// <summary>Sends what changed (call once per frame). Returns the number of datagrams sent.</summary>
        public int Flush(Bridge bridge, DateTime now)
        {
            if (bridge == null || _subscription.Count == 0)
            {
                if (_subscription.Count == 0)
                    _dirty.Clear();
                return 0;
            }
            if (!_snapshotDue && _dirty.Count == 0)
                return 0;
            if (now - _lastSend < Gap)
                return 0;
            _lastSend = now;
            var send = new List<KeyValuePair<string, object>>();
            bool full = _snapshotDue;
            if (full)
            {
                foreach (var pair in _values)
                {
                    if (Wants(pair.Key))
                        send.Add(pair);
                }
            }
            else
            {
                foreach (var key in _dirty)
                {
                    if (!Wants(key))
                        continue;
                    object v;
                    send.Add(new KeyValuePair<string, object>(key, _values.TryGetValue(key, out v) ? v : null));
                }
            }
            _dirty.Clear();
            _snapshotDue = false;
            return SendChunks(bridge, send, full);
        }

        /// <summary>JSON objects of at most <see cref="Bridge.MaxStateBytes"/> bytes; only the first chunk of a snapshot is "full".</summary>
        public static List<string> Chunks(IList<KeyValuePair<string, object>> values, int max)
        {
            var chunks = new List<string>();
            var sb = new StringBuilder("{");
            var entry = new StringBuilder();
            int bytes = 1;
            foreach (var pair in values)
            {
                entry.Length = 0;
                MiniJson.Quote(entry, pair.Key);
                entry.Append(':');
                MiniJson.Write(entry, pair.Value);
                int entryBytes = Encoding.UTF8.GetByteCount(entry.ToString());
                if (entryBytes + 2 > max)
                    continue; // one value bigger than a datagram: dropped (split it into keys)
                if (bytes > 1 && bytes + entryBytes + 2 > max)
                {
                    chunks.Add(sb.Append('}').ToString());
                    sb.Length = 0;
                    sb.Append('{');
                    bytes = 1;
                }
                if (bytes > 1)
                {
                    sb.Append(',');
                    bytes++;
                }
                sb.Append(entry);
                bytes += entryBytes;
            }
            if (sb.Length > 1 || chunks.Count == 0)
                chunks.Add(sb.Append('}').ToString());
            return chunks;
        }

        private static int SendChunks(Bridge bridge, List<KeyValuePair<string, object>> send, bool full)
        {
            var chunks = Chunks(send, Bridge.MaxStateBytes);
            for (int i = 0; i < chunks.Count; i++)
                bridge.SendState(chunks[i], full && i == 0);
            return chunks.Count;
        }

        private static bool Same(object a, object b)
        {
            if (a == null || b == null)
                return a == b;
            if (a is System.Collections.ICollection || b is System.Collections.ICollection)
                return false; // lists and maps: always resend (cheap to compare wrongly, costly to miss)
            return a.Equals(b);
        }
    }
}
