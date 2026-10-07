using System;
using System.Runtime.InteropServices;

namespace PptRunner.Desktop.Input;

public sealed class KeyboardInputController
{
    private const uint INPUT_KEYBOARD = 1;
    private const uint KEYEVENTF_KEYUP = 0x0002;

    private const ushort VK_LEFT = 0x25;
    private const ushort VK_RIGHT = 0x27;

    [StructLayout(LayoutKind.Sequential)]
    private struct INPUT
    {
        public uint type;
        public InputUnion U;
    }

    [StructLayout(LayoutKind.Explicit)]
    private struct InputUnion
    {
        // MOUSEINPUT makes the union the same size as the
        // native Windows INPUT union on both x64 and x86.
        [FieldOffset(0)]
        public MOUSEINPUT mi;

        [FieldOffset(0)]
        public KEYBDINPUT ki;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MOUSEINPUT
    {
        public int dx;
        public int dy;
        public uint mouseData;
        public uint dwFlags;
        public uint time;
        public nint dwExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct KEYBDINPUT
    {
        public ushort wVk;
        public ushort wScan;
        public uint dwFlags;
        public uint time;
        public nint dwExtraInfo;
    }

    [DllImport(
        "user32.dll",
        SetLastError = true)]
    private static extern uint SendInput(
        uint nInputs,
        INPUT[] pInputs,
        int cbSize);

    public void Next()
    {
        Console.WriteLine("[Input] Sending RIGHT");
        PressKey(VK_RIGHT);
    }

    public void Previous()
    {
        Console.WriteLine("[Input] Sending LEFT");
        PressKey(VK_LEFT);
    }

    private static void PressKey(ushort virtualKey)
    {
        var inputs = new[]
        {
            new INPUT
            {
                type = INPUT_KEYBOARD,
                U = new InputUnion
                {
                    ki = new KEYBDINPUT
                    {
                        wVk = virtualKey,
                        wScan = 0,
                        dwFlags = 0,
                        time = 0,
                        dwExtraInfo = 0
                    }
                }
            },

            new INPUT
            {
                type = INPUT_KEYBOARD,
                U = new InputUnion
                {
                    ki = new KEYBDINPUT
                    {
                        wVk = virtualKey,
                        wScan = 0,
                        dwFlags = KEYEVENTF_KEYUP,
                        time = 0,
                        dwExtraInfo = 0
                    }
                }
            }
        };

        int inputSize = Marshal.SizeOf<INPUT>();

        Console.WriteLine(
            $"[Input] INPUT size: {inputSize} bytes");

        uint sent = SendInput(
            (uint)inputs.Length,
            inputs,
            inputSize);

        Console.WriteLine(
            $"[Input] SendInput result: {sent}/{inputs.Length}");

        if (sent != inputs.Length)
        {
            int error = Marshal.GetLastWin32Error();

            throw new InvalidOperationException(
                $"SendInput failed. Windows error: {error}");
        }
    }
}