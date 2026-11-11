using GameNativeDuo.Sdk;
using GameNativeDuo.Tool;
using Xunit;

public class VersionTests
{
    /// <summary>`duo version` names the SDK the tool carries: the one its templates copy and its simulator speaks.</summary>
    [Fact]
    public void ToolReportsTheSdkVersionItCarries()
    {
        Assert.Equal(Duo.SdkVersion, Program.SdkVersion());
    }
}
