using System;
using System.Text.Json;
using PptRunner.Desktop.Input;

namespace PptRunner.Desktop.Core;

public sealed class CommandGateway
{
    private readonly KeyboardInputController _inputController;

    private readonly object _sequenceLock = new();

    private int _lastSequence;

    public event Action<string, int>? CommandExecuted;
    public event Action<string>? CommandIgnored;
    public event Action<string>? InvalidMessage;

    public CommandGateway(KeyboardInputController inputController)
    {
        _inputController = inputController;
    }

    public void HandleMessage(string message)
    {Console.WriteLine($"[Gateway] Received: {message}");
        try
        {
            var payload =
                JsonSerializer.Deserialize<CommandPayload>(message);

            if (payload is null ||
                string.IsNullOrWhiteSpace(payload.cmd))
            {
                InvalidMessage?.Invoke("Invalid command payload.");
                return;
            }

            lock (_sequenceLock)
            {
                if (payload.seq <= _lastSequence)
                {
                    CommandIgnored?.Invoke(
                        $"Duplicate/stale command: {payload.cmd} #{payload.seq}");

                    return;
                }

                _lastSequence = payload.seq;
            }

            switch (payload.cmd)
{
    case "NEXT":
        Console.WriteLine(
            $"[Gateway] Executing NEXT #{payload.seq}");

        _inputController.Next();

        CommandExecuted?.Invoke(
            "NEXT",
            payload.seq);

        break;

    case "PREVIOUS":
        Console.WriteLine(
            $"[Gateway] Executing PREVIOUS #{payload.seq}");

        _inputController.Previous();

        CommandExecuted?.Invoke(
            "PREVIOUS",
            payload.seq);

        break;

    default:
        Console.WriteLine(
            $"[Gateway] Unknown command: {payload.cmd}");

        InvalidMessage?.Invoke(
            $"Unknown command: {payload.cmd}");

        break;
}
        }
        catch (JsonException)
        {
            InvalidMessage?.Invoke("Invalid JSON command.");
        }
        catch (Exception ex)
        {
            InvalidMessage?.Invoke(
                $"Command processing failed: {ex.Message}");
        }
    }
}

public sealed class CommandPayload
{
    public string? cmd { get; set; }
    public int seq { get; set; }
}