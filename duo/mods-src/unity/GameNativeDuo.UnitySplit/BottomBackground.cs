using System;
using System.IO;
using System.Reflection;
using UnityEngine;
using UnityEngine.UI;

namespace GameNativeDuo.UnitySplit
{
    /// <summary>
    /// The bottom screen's background behind the game's UI: a solid color (our bottom UI camera's clear color) or an
    /// image (a camera of our own that draws first on the bottom screen, with one RawImage). Nothing is created until an
    /// image is chosen, and nothing runs per frame.
    /// </summary>
    internal sealed class BottomBackground
    {
        private const int Layer = 31;

        private Background _bg = Background.Default;
        private Camera _camera;
        private RawImage _image;
        private AspectRatioFitter _fitter;
        private Texture2D _texture;
        private DateTime _textureTime;

        public void Set(Background bg) => _bg = bg ?? Background.Default;

        /// <summary>Our camera, once created (the camera placer must leave it alone).</summary>
        public Camera Camera => _camera;

        /// <summary>Applies the settings to our bottom UI camera; call after the cameras exist and on every change.</summary>
        public void Apply(Layout layout, Camera bottomCam)
        {
            if (layout == null || bottomCam == null)
                return;
            bool image = _bg.UsesImage && LoadTexture();
            bottomCam.backgroundColor = _bg.UsesColor ? _bg.Color : Color.black;
            // With an image our camera clears and draws it first; the UI camera then only clears depth.
            bottomCam.clearFlags = image ? CameraClearFlags.Depth : CameraClearFlags.SolidColor;
            if (!image)
            {
                if (_camera != null)
                    _camera.enabled = false;
                return;
            }
            EnsureObjects();
            _camera.enabled = true;
            _camera.rect = layout.Viewport(layout.Bottom);
            _camera.backgroundColor = _bg.Color;
            _camera.depth = bottomCam.depth - 50f;
            _image.texture = _texture;
            _image.color = Color.Lerp(Color.white, Color.black, _bg.Dim);
            _fitter.aspectRatio = (float)_texture.width / Math.Max(1, _texture.height);
        }

        private void EnsureObjects()
        {
            if (_camera != null)
                return;
            var go = new GameObject("GameNativeDuo background camera");
            UnityEngine.Object.DontDestroyOnLoad(go);
            go.transform.position = new Vector3(-31000f, -31000f, -31000f);
            _camera = go.AddComponent<Camera>();
            _camera.clearFlags = CameraClearFlags.SolidColor;
            _camera.cullingMask = 1 << Layer;
            _camera.orthographic = true;
            _camera.nearClipPlane = 0.1f;
            _camera.farClipPlane = 100f;
            _camera.useOcclusionCulling = false;
            _camera.allowHDR = false;
            _camera.allowMSAA = false;

            var canvasGo = new GameObject("GameNativeDuo background");
            UnityEngine.Object.DontDestroyOnLoad(canvasGo);
            canvasGo.layer = Layer;
            canvasGo.AddComponent<DuoOwned>();
            Canvas canvas = canvasGo.AddComponent<Canvas>();
            canvas.renderMode = RenderMode.ScreenSpaceCamera;
            canvas.worldCamera = _camera;
            canvas.planeDistance = 10f;

            var imageGo = new GameObject("Image");
            imageGo.layer = Layer;
            imageGo.transform.SetParent(canvasGo.transform, false);
            _image = imageGo.AddComponent<RawImage>();
            _image.raycastTarget = false;
            _fitter = imageGo.AddComponent<AspectRatioFitter>();
            _fitter.aspectMode = AspectRatioFitter.AspectMode.EnvelopeParent;
        }

        /// <summary>Loads background.jpg when it changed; false when there is none or it cannot be read.</summary>
        private bool LoadTexture()
        {
            try
            {
                string path = Background.ImagePath;
                if (!File.Exists(path))
                    return false;
                DateTime time = File.GetLastWriteTimeUtc(path);
                if (_texture != null && time == _textureTime)
                    return true;
                var texture = new Texture2D(2, 2, TextureFormat.RGB24, false);
                if (!LoadImage(texture, File.ReadAllBytes(path)))
                {
                    UnityEngine.Object.Destroy(texture);
                    return false;
                }
                texture.wrapMode = TextureWrapMode.Clamp;
                if (_texture != null)
                    UnityEngine.Object.Destroy(_texture);
                _texture = texture;
                _textureTime = time;
                return true;
            }
            catch (Exception ex)
            {
                Plugin.Log.LogWarning("Background image could not be loaded: " + ex.Message);
                return false;
            }
        }

        /// <summary>ImageConversion.LoadImage (Unity 2017.1+) or Texture2D.LoadImage (older), whichever exists.</summary>
        private static bool LoadImage(Texture2D texture, byte[] bytes)
        {
            Type conversion = Type.GetType("UnityEngine.ImageConversion, UnityEngine.ImageConversionModule")
                ?? Type.GetType("UnityEngine.ImageConversion, UnityEngine");
            MethodInfo m = conversion?.GetMethod("LoadImage", new[] { typeof(Texture2D), typeof(byte[]) });
            if (m != null)
                return (bool)m.Invoke(null, new object[] { texture, bytes });
            m = typeof(Texture2D).GetMethod("LoadImage", new[] { typeof(byte[]) });
            return m != null && (bool)m.Invoke(texture, new object[] { bytes });
        }
    }
}
