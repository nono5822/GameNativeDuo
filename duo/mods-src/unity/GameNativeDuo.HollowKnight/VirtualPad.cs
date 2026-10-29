using System;
using System.Collections.Generic;
using InControl;
using UnityEngine;

namespace GameNativeDuo.HollowKnight
{
    /// <summary>
    /// Test-mode only: a gamepad of ours, attached to InControl like a real one, so a test presses the map button
    /// through exactly the path a pad press takes (InControl device -> PlayerAction -> every listener in the game).
    /// The control pressed is the one the player's quick-map binding uses.
    /// </summary>
    internal sealed class VirtualPad : InputDevice
    {
        private static readonly InputControlType[] Buttons =
        {
            InputControlType.Action1, InputControlType.Action2, InputControlType.Action3, InputControlType.Action4,
            InputControlType.LeftBumper, InputControlType.RightBumper, InputControlType.LeftTrigger, InputControlType.RightTrigger,
            InputControlType.Back, InputControlType.Start, InputControlType.Select, InputControlType.Menu, InputControlType.View,
            InputControlType.Options, InputControlType.Share, InputControlType.Minus, InputControlType.Plus,
            InputControlType.TouchPadButton, InputControlType.LeftStickButton, InputControlType.RightStickButton,
            InputControlType.DPadUp, InputControlType.DPadDown, InputControlType.DPadLeft, InputControlType.DPadRight,
        };

        private static VirtualPad _pad;
        private readonly HashSet<InputControlType> _down = new HashSet<InputControlType>();

        private VirtualPad()
            : base("GameNativeDuo test pad")
        {
            foreach (InputControlType t in Buttons)
                AddControl(t, t.ToString());
        }

        public override void Update(ulong updateTick, float deltaTime)
        {
            foreach (InputControlType t in Buttons)
                UpdateWithState(t, _down.Contains(t), updateTick, deltaTime);
        }

        /// <summary>A game action by its HeroActions field name (quickMap, openInventory, pause, jump, ...).</summary>
        internal static PlayerAction Action(string name)
        {
            System.Reflection.FieldInfo f = typeof(HeroActions).GetField(name);
            return f != null ? f.GetValue(InputHandler.Instance.inputActions) as PlayerAction : null;
        }

        /// <summary>The pad control an action is bound to (the player's binding, else the game's default).</summary>
        internal static InputControlType ControlFor(string actionName)
        {
            PlayerAction action = Action(actionName);
            if (action == null)
                return InputControlType.None;
            foreach (BindingSource b in action.Bindings)
            {
                var device = b as DeviceBindingSource;
                if (device != null)
                    return device.Control;
            }
            return InputControlType.None;
        }

        /// <summary>Plugs the pad in (once): the game then maps its controller buttons, quick map included.</summary>
        internal static void Attach()
        {
            if (_pad != null)
                return;
            _pad = new VirtualPad();
            InputManager.AttachDevice(_pad);
            Plugin.Log.LogInfo("Test pad attached");
        }

        internal static void Press(InputControlType control, bool down)
        {
            Attach();
            if (down)
                _pad._down.Add(control);
            else
                _pad._down.Remove(control);
        }

        internal static string Describe(string actionName)
        {
            PlayerAction action = Action(actionName);
            if (action == null)
                return "no action " + actionName;
            var s = new System.Text.StringBuilder(actionName + " bindings:");
            foreach (BindingSource b in action.Bindings)
                s.Append(' ').Append(b.GetType().Name).Append('(').Append(b.Name).Append(')');
            s.Append(", pressed ").Append(action.IsPressed).Append(", active device ").Append(InputManager.ActiveDevice != null ? InputManager.ActiveDevice.Name : "none")
                .Append(", set device ").Append(InputHandler.Instance.inputActions.Device != null ? InputHandler.Instance.inputActions.Device.Name : "any");
            return s.ToString();
        }
    }

    /// <summary>Runs timed presses of the test pad (tap, hold, spam), one step per frame.</summary>
    internal sealed class PadScript : MonoBehaviour
    {
        private readonly Queue<KeyValuePair<float, bool>> _steps = new Queue<KeyValuePair<float, bool>>();
        private InputControlType _control;
        private string _action;
        private float _at;

        /// <summary>Queues presses of one action (one script per action, so presses of different buttons can overlap), after <paramref name="delay"/> s.</summary>
        internal static void Run(GameObject host, string action, string mode, float arg, float delay)
        {
            VirtualPad.Attach();
            PadScript s = null;
            foreach (PadScript p in host.GetComponents<PadScript>())
            {
                if (p._action == action)
                    s = p;
            }
            if (s == null)
                s = host.AddComponent<PadScript>();
            s._action = action;
            s._control = InputControlType.None; // found on the first step: the game maps a new pad's buttons a frame later
            s._steps.Clear();
            s._at = Time.unscaledTime + 0.5f + delay;
            switch (mode)
            {
                case "hold": // hold for arg seconds
                    s._steps.Enqueue(new KeyValuePair<float, bool>(0f, true));
                    s._steps.Enqueue(new KeyValuePair<float, bool>(Mathf.Max(0.05f, arg), false));
                    break;
                case "spam": // arg presses, 4 per second
                    for (int i = 0; i < Mathf.Max(1, (int)arg); i++)
                    {
                        s._steps.Enqueue(new KeyValuePair<float, bool>(i == 0 ? 0f : 0.125f, true));
                        s._steps.Enqueue(new KeyValuePair<float, bool>(0.125f, false));
                    }
                    break;
                default: // tap: about 0.1 s, a quick real press
                    s._steps.Enqueue(new KeyValuePair<float, bool>(0f, true));
                    s._steps.Enqueue(new KeyValuePair<float, bool>(0.1f, false));
                    break;
            }
            Plugin.Log.LogInfo("  press " + action + " " + mode + " queued");
        }

        private void Update()
        {
            try
            {
                if (_steps.Count > 0 && _control == InputControlType.None && Time.unscaledTime >= _at)
                {
                    _control = VirtualPad.ControlFor(_action);
                    Plugin.Log.LogInfo("  pad control " + _control + ": " + VirtualPad.Describe(_action));
                    if (_control == InputControlType.None)
                    {
                        Plugin.Log.LogWarning("  " + _action + " has no pad binding");
                        _steps.Clear();
                        return;
                    }
                }
                while (_steps.Count > 0 && Time.unscaledTime >= _at + _steps.Peek().Key)
                {
                    KeyValuePair<float, bool> step = _steps.Dequeue();
                    _at += step.Key;
                    VirtualPad.Press(_control, step.Value);
                    Plugin.Log.LogInfo("  pad " + _control + (step.Value ? " down" : " up") + " frame " + Time.frameCount);
                }
            }
            catch (Exception ex)
            {
                Plugin.Log.LogError("Test pad failed: " + ex);
                _steps.Clear();
            }
        }
    }
}
