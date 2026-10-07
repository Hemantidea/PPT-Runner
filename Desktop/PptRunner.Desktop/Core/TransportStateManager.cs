using System;

namespace PptRunner.Desktop.Core;

public sealed class TransportStateManager
{
    private readonly object _lock = new();

    private bool _localConnected;
    private bool _relayMobileConnected;

    private TransportState _state = TransportState.Waiting;

    public TransportState State
    {
        get
        {
            lock (_lock)
            {
                return _state;
            }
        }
    }

    public event Action<TransportState>? StateChanged;

    public void SetLocalConnected(bool connected)
    {
        Update(localConnected: connected);
    }

    public void SetRelayMobileConnected(bool connected)
    {
        Update(relayMobileConnected: connected);
    }

    private void Update(
        bool? localConnected = null,
        bool? relayMobileConnected = null)
    {
        TransportState newState;

        lock (_lock)
        {
            if (localConnected.HasValue)
                _localConnected = localConnected.Value;

            if (relayMobileConnected.HasValue)
                _relayMobileConnected = relayMobileConnected.Value;

            if (_localConnected)
            {
                newState = TransportState.Local;
            }
            else if (_relayMobileConnected)
            {
                newState = TransportState.Relay;
            }
            else
            {
                newState = TransportState.Waiting;
            }

            if (newState == _state)
                return;

            _state = newState;
        }

        StateChanged?.Invoke(newState);
    }
}