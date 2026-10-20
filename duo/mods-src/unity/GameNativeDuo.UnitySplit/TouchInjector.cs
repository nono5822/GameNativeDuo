using System.Collections.Generic;
using UnityEngine;
using UnityEngine.EventSystems;

namespace GameNativeDuo.UnitySplit
{
    /// <summary>
    /// Bottom-screen touches delivered straight to uGUI, as a touchscreen would: down, up, click, drag and drop go to
    /// whatever is under each finger, through the EventSystem's own raycasters. The OS mouse is never touched, so
    /// there is no cursor, the game does not switch its button prompts to keyboard and mouse, a tap does not count as
    /// a mouse click in gameplay (in Slime Rancher that would fire the vacpack), and it works while the game has the
    /// mouse locked. Independent of the game's input module (Slime Rancher's has no touch support at all).
    /// Mirrors uGUI's PointerInputModule touch handling. Main thread only.
    /// </summary>
    internal sealed class TouchInjector
    {
        /// <summary>uGUI uses -1..-3 for mouse buttons and 0+ for touches; stay clear of the game's own touch ids.</summary>
        private const int PointerIdBase = 0x4744; // "GD"

        private const float DoubleClickSeconds = 0.3f;

        private sealed class Finger
        {
            public PointerEventData Data;
            public GameObject LastPress;
            public float LastPressTime;
            public int ClickCount;
        }

        private readonly Dictionary<int, Finger> _fingers = new Dictionary<int, Finger>();
        private readonly List<RaycastResult> _hits = new List<RaycastResult>();
        private readonly UiRouter _router;

        public TouchInjector(UiRouter router)
        {
            _router = router;
        }

        /// <summary>Handles one bridge touch; (<paramref name="x"/>, <paramref name="y"/>) are window pixels from the top-left.</summary>
        public void Handle(byte action, int pointer, float x, float y)
        {
            EventSystem es = EventSystem.current;
            if (es == null)
                return;
            var pos = new Vector2(x, Screen.height - y);
            Finger f;
            _fingers.TryGetValue(pointer, out f);
            switch (action)
            {
                case Bridge.TouchDown:
                    if (f == null)
                        _fingers[pointer] = f = new Finger();
                    if (f.Data != null)
                        Release(f, false); // a lost UP: finish the old gesture first
                    Press(es, f, pointer, pos);
                    break;
                case Bridge.TouchMove:
                    if (f != null && f.Data != null)
                        Move(f, pos);
                    break;
                case Bridge.TouchUp:
                case Bridge.TouchCancel:
                    if (f != null && f.Data != null)
                    {
                        Move(f, pos);
                        Release(f, action == Bridge.TouchUp);
                    }
                    break;
            }
        }

        /// <summary>Ends every gesture without clicks (screen split turned off, scene change).</summary>
        public void CancelAll()
        {
            foreach (Finger f in _fingers.Values)
            {
                if (f.Data != null)
                    Release(f, false);
            }
        }

        private void Press(EventSystem es, Finger f, int pointer, Vector2 pos)
        {
            var d = new PointerEventData(es)
            {
                pointerId = PointerIdBase + pointer,
                position = pos,
                pressPosition = pos,
                delta = Vector2.zero,
                button = PointerEventData.InputButton.Left,
                useDragThreshold = true,
                eligibleForClick = true,
            };
            f.Data = d;
            GameObject target = Raycast(es, d);
            d.pointerPressRaycast = d.pointerCurrentRaycast;
            Hover(d, target);

            GameObject pressed = ExecuteEvents.ExecuteHierarchy(target, d, ExecuteEvents.pointerDownHandler);
            if (pressed == null)
                pressed = ExecuteEvents.GetEventHandler<IPointerClickHandler>(target);
            float now = Time.unscaledTime;
            f.ClickCount = pressed != null && pressed == f.LastPress && now - f.LastPressTime < DoubleClickSeconds ? f.ClickCount + 1 : 1;
            d.clickCount = f.ClickCount;
            d.clickTime = now;
            f.LastPress = pressed;
            f.LastPressTime = now;
            d.pointerPress = pressed;
            d.rawPointerPress = target;
            d.pointerDrag = ExecuteEvents.GetEventHandler<IDragHandler>(target);
            if (d.pointerDrag != null)
                ExecuteEvents.Execute(d.pointerDrag, d, ExecuteEvents.initializePotentialDrag);
        }

        private void Move(Finger f, Vector2 pos)
        {
            PointerEventData d = f.Data;
            d.delta = pos - d.position;
            d.position = pos;
            EventSystem es = EventSystem.current;
            if (es == null)
                return;
            Hover(d, Raycast(es, d));
            if (d.pointerDrag == null)
                return;
            if (!d.dragging)
            {
                float threshold = es.pixelDragThreshold;
                if (d.useDragThreshold && (d.pressPosition - pos).sqrMagnitude < threshold * threshold)
                    return;
                ExecuteEvents.Execute(d.pointerDrag, d, ExecuteEvents.beginDragHandler);
                d.dragging = true;
            }
            // A press that turned into a drag no longer clicks.
            if (d.pointerPress != d.pointerDrag)
            {
                ExecuteEvents.Execute(d.pointerPress, d, ExecuteEvents.pointerUpHandler);
                d.eligibleForClick = false;
                d.pointerPress = null;
                d.rawPointerPress = null;
            }
            ExecuteEvents.Execute(d.pointerDrag, d, ExecuteEvents.dragHandler);
        }

        private void Release(Finger f, bool completed)
        {
            PointerEventData d = f.Data;
            f.Data = null;
            GameObject over = d.pointerCurrentRaycast.gameObject;
            ExecuteEvents.Execute(d.pointerPress, d, ExecuteEvents.pointerUpHandler);
            GameObject clickHandler = ExecuteEvents.GetEventHandler<IPointerClickHandler>(over);
            if (completed && d.pointerPress != null && d.pointerPress == clickHandler && d.eligibleForClick)
                ExecuteEvents.Execute(d.pointerPress, d, ExecuteEvents.pointerClickHandler);
            else if (completed && d.pointerDrag != null && d.dragging)
                ExecuteEvents.ExecuteHierarchy(over, d, ExecuteEvents.dropHandler);
            if (d.pointerDrag != null && d.dragging)
                ExecuteEvents.Execute(d.pointerDrag, d, ExecuteEvents.endDragHandler);
            d.eligibleForClick = false;
            d.pointerPress = null;
            d.rawPointerPress = null;
            d.pointerDrag = null;
            d.dragging = false;
            // A finger that lifts leaves nothing hovered (no stuck highlight).
            Hover(d, null);
        }

        private GameObject Raycast(EventSystem es, PointerEventData d)
        {
            _hits.Clear();
            es.RaycastAll(d, _hits);
            RaycastResult best = default(RaycastResult);
            for (int i = 0; i < _hits.Count; i++)
            {
                GameObject go = _hits[i].gameObject;
                if (go != null && !_router.IsHidden(go))
                {
                    best = _hits[i];
                    break;
                }
            }
            _hits.Clear();
            d.pointerCurrentRaycast = best;
            return best.gameObject;
        }

        /// <summary>Pointer enter/exit along the hierarchy, like BaseInputModule.HandlePointerExitAndEnter.</summary>
        private static void Hover(PointerEventData d, GameObject target)
        {
            GameObject old = d.pointerEnter;
            if (old == target)
                return;
            Transform common = CommonRoot(old, target);
            if (old != null)
            {
                for (Transform t = old.transform; t != null && t != common; t = t.parent)
                {
                    ExecuteEvents.Execute(t.gameObject, d, ExecuteEvents.pointerExitHandler);
                    d.hovered.Remove(t.gameObject);
                }
            }
            d.pointerEnter = target;
            if (target != null)
            {
                for (Transform t = target.transform; t != null && t != common; t = t.parent)
                {
                    ExecuteEvents.Execute(t.gameObject, d, ExecuteEvents.pointerEnterHandler);
                    d.hovered.Add(t.gameObject);
                }
            }
        }

        private static Transform CommonRoot(GameObject a, GameObject b)
        {
            if (a == null || b == null)
                return null;
            for (Transform ta = a.transform; ta != null; ta = ta.parent)
            {
                for (Transform tb = b.transform; tb != null; tb = tb.parent)
                {
                    if (ta == tb)
                        return ta;
                }
            }
            return null;
        }
    }
}
