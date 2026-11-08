namespace GameNativeDuo.Tool;

/// <summary>Window capture for the sim's screen pictures (C3). Null until a capture backend is available.</summary>
public static class Capture
{
    public static byte[]? Screen(SimHost sim, string which, string? window) => null;
}
