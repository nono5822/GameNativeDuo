using System;
using GameNativeDuo.UnitySplit;
using UnityEngine;

namespace GameNativeDuo.HollowKnight
{
    /// <summary>
    /// The game keeps running while the inventory is open (a hit closes it), but Hollow Knight stops drawing the
    /// world then: on one screen the inventory covers it anyway. With the inventory on the bottom screen that left the
    /// top screen black, so you could not see what was coming. While the split is on, the world camera keeps drawing
    /// on the top screen; the game turns it back on itself when the inventory closes.
    /// </summary>
    internal sealed class WorldOnTop : MonoBehaviour
    {
        private Camera _forced;
        private bool _loggedState;

        private void LateUpdate()
        {
            try
            {
                Camera world = WorldCamera();
                bool want = Settings.WorldOnTop && SplitActive() && world != null && LiveMap.InventoryIsOpen();
                if (want)
                {
                    if (!_loggedState)
                    {
                        _loggedState = true;
                        Plugin.Log.LogInfo("Inventory open: world camera enabled=" + world.enabled + ", object active=" + world.gameObject.activeInHierarchy);
                    }
                    if (!world.enabled)
                    {
                        world.enabled = true;
                        _forced = world;
                    }
                }
                else
                {
                    _loggedState = false;
                    if (_forced != null)
                    {
                        // The inventory closed: the game re-enables its camera itself; only undo ours if it did not.
                        _forced = null;
                    }
                }
            }
            catch (Exception ex)
            {
                Plugin.Log.LogError("World-on-top update failed: " + ex);
                enabled = false;
            }
        }

        private static bool SplitActive()
        {
            Split split = Split.Instance;
            return split != null && split.Active;
        }

        private static Camera WorldCamera()
        {
            GameCameras cams = GameCameras.instance;
            if (cams == null || cams.tk2dCam == null)
                return null;
            return cams.tk2dCam.GetComponent<Camera>();
        }
    }
}
