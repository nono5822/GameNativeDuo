using GameNativeDuo.Sdk;
using GameNativeDuo.UnitySplit;
using Xunit;

namespace GameNativeDuo.UnitySplit
{
    /// <summary>Stands in for the plugin's BepInEx logger.</summary>
    internal static class Plugin
    {
        public static readonly Logger Log = new Logger();

        internal sealed class Logger
        {
            public string Last = "";
            public void LogWarning(string s) => Last = s;
        }
    }
}

/// <summary>Pretend game types for the state reader.</summary>
public class FakeContext
{
    public static FakeContext Instance = new FakeContext();
    public FakePlayer Player = new FakePlayer();
    private int _secret = 7;
    public int Secret() => _secret;
    public static int PrivateStatic => 1;
}

public class FakePlayer
{
    public int Money { get; set; } = 120;
    public string Zone = "Reef";
    public FakeMode Mode = FakeMode.Classic;
    public float[] Arr = { 1 };
    private int hidden = 3;
}

public enum FakeMode { Classic, Rush }

public class GameProfileTests
{
    private const string Json = @"{""format"":""gamenative-duo/game-profile"",""version"":1,""game"":""steam-433340"",""engine"":""unity-ugui"",
      ""builds"":[""1.4.4""],
      ""cameras"":{""ui"":[{""name"":""UI*Cam""}],""world"":[{""name"":""Main Camera""}]},
      ""canvases"":[{""path"":""HUD Root/**"",""screen"":""top""},{""path"":""Pause*"",""screen"":""hidden""}],
      ""stayOnTop"":[""*/crossHair""],
      ""elements"":{""HUD/Score"":{""name"":""Score"",""features"":{""scale"":false}}},
      ""state"":[{""key"":""sr.money"",""from"":{""type"":""FakeContext"",""member"":""Instance.Player.Money""}},
                 {""key"":""sr.zone"",""from"":{""type"":""FakeContext"",""member"":""Instance.Player.Zone""}},
                 {""key"":""sr.mode"",""from"":{""type"":""FakeContext"",""member"":""Instance.Player.Mode""}},
                 {""key"":""sr.hidden"",""from"":{""type"":""FakeContext"",""member"":""Instance.Player.hidden""}},
                 {""key"":""sr.secret"",""from"":{""type"":""FakeContext"",""member"":""Instance._secret""}},
                 {""key"":""sr.arr"",""from"":{""type"":""FakeContext"",""member"":""Instance.Player.Arr""}},
                 {""key"":""sr.sys"",""from"":{""type"":""System.Environment"",""member"":""MachineName""}}]}";

    [Fact]
    public void CamerasCanvasesTopAndElements()
    {
        var p = GameProfile.Parse(Json);
        Assert.True(p.IsUiCamera("UIHudCam", 0) ?? false);
        Assert.False(p.IsUiCamera("Main Camera", -1) ?? true);
        Assert.Null(p.IsUiCamera("Other", -1));
        Assert.Equal(DuoScreen.Top, p.CanvasScreen("HUD Root/HudUI/Inner"));
        Assert.Equal(DuoScreen.Hidden, p.CanvasScreen("Pause Menu"));
        Assert.Null(p.CanvasScreen("Pause/Menu"));
        Assert.True(p.StaysOnTop("HUD/crosshair"));
        Assert.False(p.StaysOnTop("HUD/A/crossHair"));
        Assert.Equal("Score", p.Element("HUD/Score").Name);
        Assert.Equal("{\"scale\":false}", p.Element("HUD/Score").FeaturesJson);
        Assert.True(p.BuildMatches("1.4.4"));
        Assert.False(p.BuildMatches("1.5"));
        Assert.True(GameProfile.Parse("{}").IsEmpty);
    }

    [Fact]
    public void StateReadsPublicMembersOnly()
    {
        var p = GameProfile.Parse(Json);
        object v;
        Assert.True(StateReader.TryRead(p.State[0], out v));
        Assert.Equal(120.0, v);
        Assert.True(StateReader.TryRead(p.State[1], out v));
        Assert.Equal("Reef", v);
        Assert.True(StateReader.TryRead(p.State[2], out v));
        Assert.Equal("Classic", v);
        Assert.False(StateReader.TryRead(p.State[3], out v)); // private field
        Assert.Contains("no public", Plugin.Log.Last);
        Assert.False(StateReader.TryRead(p.State[4], out v)); // private field (and Secret() is a method: never called)
        Assert.False(StateReader.TryRead(p.State[5], out v)); // arrays are not state
        Assert.False(StateReader.TryRead(p.State[6], out v)); // a runtime type, not the game's
        Assert.Contains("no type", Plugin.Log.Last);
    }
}
