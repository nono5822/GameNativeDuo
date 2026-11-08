using System.Text.Json.Nodes;

namespace GameNativeDuo.Tool;

/// <summary>
/// Numbered events for the Duo API's long poll (`GET /api/events?since=N&amp;wait=S`): logs, bridge changes, element
/// changes, state key names, check progress. Keeps the newest <see cref="Capacity"/>. Thread-safe.
/// </summary>
public sealed class EventLog
{
    public const int Capacity = 2000;

    private readonly LinkedList<JsonObject> _events = new();
    private long _seq;
    private readonly object _lock = new();

    public long Last
    {
        get { lock (_lock) return _seq; }
    }

    public void Add(string kind, JsonObject data)
    {
        lock (_lock)
        {
            data["seq"] = ++_seq;
            data["t"] = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            data["kind"] = kind;
            _events.AddLast(data);
            while (_events.Count > Capacity)
                _events.RemoveFirst();
            Monitor.PulseAll(_lock);
        }
    }

    public void Log(string source, string level, string text) =>
        Add("log", new JsonObject { ["source"] = source, ["level"] = level, ["text"] = text });

    /// <summary>Events after <paramref name="since"/> (of <paramref name="kind"/> when given), waiting up to <paramref name="wait"/> for one.</summary>
    public (long next, JsonArray events) Since(long since, TimeSpan wait, string? kind = null)
    {
        var deadline = DateTime.UtcNow + wait;
        lock (_lock)
        {
            while (true)
            {
                var list = new JsonArray();
                foreach (var e in _events)
                {
                    if ((long)e["seq"]! > since && (kind == null || (string?)e["kind"] == kind))
                        list.Add(e.DeepClone());
                }
                var left = deadline - DateTime.UtcNow;
                if (list.Count > 0 || left <= TimeSpan.Zero)
                    return (_seq, list);
                Monitor.Wait(_lock, left);
            }
        }
    }
}
