// A BepInEx 5 plugin that puts one of a Unity game's UI objects on the bottom screen with the Duo SDK.
// Build: duo/sdk/examples/unity-basic (dotnet build -p:ManagedPath=<Game_Data/Managed> -p:BepInExPath=<BepInEx/core>).
using BepInEx;
using GameNativeDuo.Sdk;
using UnityEngine;

[BepInPlugin("example.duo.unity", "Duo SDK example", "1.0.0")]
public class DuoExample : BaseUnityPlugin
{
    private const string Target = "HUD/Canvas/Score"; // the game's object, by path
    private RectTransform _score;
    private Vector2 _home;
    private int _rulesSeen = -1;

    private void Awake()
    {
        Duo.LocalLog = Logger.LogInfo;
        Duo.Start("unity-example");
        Duo.Elements.Register("Score", "Score", () => _score == null ? (DuoRect?)null : WindowRect(_score),
            new ElementFeatures { Scale = false }, DuoScreen.Bottom, "In a level");
        Duo.OnCommand("example.ping", args => CommandResult.Ok("pong"));
    }

    private void Update()
    {
        Duo.Tick(Screen.width, Screen.height);
        if (_score == null)
        {
            var go = GameObject.Find(Target);
            if (go == null)
                return;
            _score = go.GetComponent<RectTransform>();
            _home = _score.anchoredPosition;
            Duo.Elements.Changed();
        }
        if (Duo.RulesVersion == _rulesSeen || !Duo.IsSplit)
            return;
        _rulesSeen = Duo.RulesVersion;
        // Apply the user's rule: shift by fractions of the bottom screen (a real adapter also moves it between screens).
        var rule = Duo.Rule("Score");
        var b = Duo.Layout.Bottom;
        _score.anchoredPosition = _home + (rule == null ? Vector2.zero : new Vector2(rule.X * b.W, -rule.Y * b.H));
        _score.gameObject.SetActive(rule == null || rule.Screen != DuoScreen.Hidden);
        Duo.Publish("example.level", UnityEngine.SceneManagement.SceneManager.GetActiveScene().name);
    }

    private static DuoRect WindowRect(RectTransform t)
    {
        var c = new Vector3[4];
        t.GetWorldCorners(c); // screen space overlay canvas: world = screen pixels, origin bottom-left
        return new DuoRect((int)c[0].x, Screen.height - (int)c[1].y, (int)(c[2].x - c[0].x), (int)(c[1].y - c[0].y));
    }

    private void OnDestroy() => Duo.Stop();
}
