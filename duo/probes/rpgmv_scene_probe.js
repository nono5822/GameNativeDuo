// GameNativeDuo probe for RPG Maker MV games (tested target: OMORI). Read-only: it only logs.
//
// Writes the scene graph (scenes, layers, windows, positions) to duo_probe.log next to the game's .exe
// whenever a scene starts, a message opens, or every 15 s, so we can see which UI lives in the
// WindowLayer before splitting it onto the bottom screen.
//
// Install: copy to <game>/www/js/duo/ and add after the main.js line in www/index.html:
//   <script type="text/javascript" src="js/duo/rpgmv_scene_probe.js"></script>
(function () {
    var fs = require('fs');
    var path = require('path');
    var out = path.join(path.dirname(process.execPath), 'duo_probe.log');

    function log(line) {
        try {
            fs.appendFileSync(out, new Date().toISOString().substr(11, 12) + ' ' + line + '\n');
        } catch (e) {}
    }

    function describe(o) {
        var name = (o.constructor && o.constructor.name) || '?';
        var s = name + ' @' + Math.round(o.x || 0) + ',' + Math.round(o.y || 0);
        if (typeof o.width === 'number') s += ' ' + Math.round(o.width) + 'x' + Math.round(o.height);
        if (o.visible === false) s += ' HIDDEN';
        if (typeof o.openness === 'number') s += ' open=' + o.openness;
        if (typeof o.alpha === 'number' && o.alpha !== 1) s += ' alpha=' + o.alpha.toFixed(2);
        return s;
    }

    function dump(o, depth, indent) {
        var kids = o.children || [];
        log(indent + describe(o) + (kids.length ? ' [' + kids.length + ']' : ''));
        if (depth <= 0) return;
        var max = 30;
        for (var i = 0; i < Math.min(kids.length, max); i++) dump(kids[i], depth - 1, indent + '  ');
        if (kids.length > max) log(indent + '  ... ' + (kids.length - max) + ' more');
    }

    var lastDump = 0;
    function dumpScene(reason, force) {
        try {
            var now = Date.now();
            if (!force && now - lastDump < 3000) return;
            lastDump = now;
            var scene = SceneManager._scene;
            if (!scene) return;
            log('=== ' + reason + ': ' + scene.constructor.name + '  Graphics ' + Graphics.width + 'x' + Graphics.height +
                '  box ' + Graphics.boxWidth + 'x' + Graphics.boxHeight);
            dump(scene, 2, '');
            if (scene._windowLayer) {
                log('--- WindowLayer');
                dump(scene._windowLayer, 2, '  ');
            } else {
                log('--- no _windowLayer on this scene');
            }
        } catch (e) {
            log('dump failed: ' + e);
        }
    }

    var gameOnload = window.onload;
    window.onload = function () {
        log('probe loaded; renderer=' + (Graphics._renderer && Graphics._renderer.constructor.name));
        try {
            var onSceneStart = SceneManager.onSceneStart;
            SceneManager.onSceneStart = function () {
                onSceneStart.apply(this, arguments);
                setTimeout(function () { dumpScene('scene start +2s', true); }, 2000);
            };
            var startMessage = Window_Message.prototype.startMessage;
            Window_Message.prototype.startMessage = function () {
                startMessage.apply(this, arguments);
                var self = this;
                setTimeout(function () {
                    log('message window: ' + describe(self) + ' parent=' + (self.parent && self.parent.constructor.name));
                    dumpScene('message', false);
                }, 400);
            };
            setInterval(function () { dumpScene('periodic', false); }, 15000);
        } catch (e) {
            log('hook failed: ' + e);
        }
        if (gameOnload) return gameOnload.apply(this, arguments);
    };
})();
