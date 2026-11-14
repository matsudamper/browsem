// navigator.mediaDevices.getUserMedia を差し替え、サイトごとの設定（real/image/deny）に応じて
// カメラ映像を設定した画像に置き換えるコンテンツスクリプト。
// document_start で実行され、ページのスクリプトより先に getUserMedia を差し替える。
(function () {
  'use strict';

  const pageWin = window.wrappedJSObject;
  const pageMediaDevices = pageWin.navigator.mediaDevices;
  if (!pageMediaDevices || typeof pageMediaDevices.getUserMedia !== 'function') return;

  // 差し替え前のページ側 getUserMedia。実カメラ・マイクの取得に使う
  const origGetUserMedia = pageMediaDevices.getUserMedia;

  const FRAMES_PER_SECOND = 15;
  const PLACEHOLDER_WIDTH = 640;
  const PLACEHOLDER_HEIGHT = 480;

  function callOrigGetUserMedia(constraints) {
    return origGetUserMedia.call(pageMediaDevices, constraints);
  }

  function requestsVideo(constraints) {
    return !!(constraints && constraints.video);
  }

  function requestsAudio(constraints) {
    return !!(constraints && constraints.audio);
  }

  // ページ側から読める DOMException を作る。コンテンツスクリプト側の Error はページから読めない
  function pageDomException(message, name) {
    return new pageWin.DOMException(message, name);
  }

  // ネイティブ側にこのサイトのカメラの扱いを問い合わせる。
  // 未設定の場合はネイティブ側で確認ダイアログを表示し、選択されるまで応答しない
  function resolveCameraMode() {
    return browser.runtime.sendNativeMessage('cameraReplaceBridge', { action: 'resolveCamera' });
  }

  async function loadImageBitmap(imageDataUrl) {
    const base64 = imageDataUrl.substring(imageDataUrl.indexOf(',') + 1);
    const binary = atob(base64);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
      bytes[i] = binary.charCodeAt(i);
    }
    // data URL を img に読ませるとページの CSP で拒否されうるため、Blob から直接デコードする
    return createImageBitmap(new Blob([bytes], { type: 'image/jpeg' }));
  }

  function drawPlaceholder(context, width, height) {
    context.fillStyle = '#303030';
    context.fillRect(0, 0, width, height);
    context.fillStyle = '#b0b0b0';
    context.font = '32px sans-serif';
    context.textAlign = 'center';
    context.textBaseline = 'middle';
    context.fillText('No Image', width / 2, height / 2);
  }

  // 画像を描き続ける canvas の映像トラックを持つ MediaStream を作る
  async function createImageStream(imageDataUrl) {
    const bitmap = imageDataUrl ? await loadImageBitmap(imageDataUrl) : null;
    const canvas = document.createElement('canvas');
    canvas.width = bitmap ? bitmap.width : PLACEHOLDER_WIDTH;
    canvas.height = bitmap ? bitmap.height : PLACEHOLDER_HEIGHT;
    const context = canvas.getContext('2d');
    const draw = function () {
      if (bitmap) {
        context.drawImage(bitmap, 0, 0);
      } else {
        drawPlaceholder(context, canvas.width, canvas.height);
      }
    };
    draw();
    const stream = canvas.captureStream(FRAMES_PER_SECOND);
    const videoTrack = stream.getVideoTracks()[0];
    // 静止画でも描き直さないと captureStream が新しいフレームを出さないため、定期的に描き直す
    const timer = setInterval(function () {
      if (videoTrack.readyState === 'ended') {
        clearInterval(timer);
        return;
      }
      draw();
    }, 1000 / FRAMES_PER_SECOND);
    return stream;
  }

  // カメラを画像に置き換えたストリームを作る。マイクも要求されていれば実マイクのトラックを加える
  async function createReplacedStream(constraints, imageDataUrl) {
    const stream = await createImageStream(imageDataUrl);
    if (requestsAudio(constraints)) {
      let audioStream;
      try {
        audioStream = await callOrigGetUserMedia(cloneInto({ audio: constraints.audio }, pageWin));
      } catch (e) {
        stream.getTracks().forEach(function (track) { track.stop(); });
        throw e;
      }
      const audioTracks = audioStream.getAudioTracks();
      for (let i = 0; i < audioTracks.length; i++) {
        stream.addTrack(audioTracks[i]);
      }
    }
    return stream;
  }

  function toPageError(e) {
    const name = (e && e.name) ? String(e.name) : 'NotReadableError';
    const message = (e && e.message) ? String(e.message) : 'Could not start video source';
    return pageDomException(message, name);
  }

  function handleGetUserMedia(constraints) {
    return new pageWin.Promise(exportFunction(function (resolve, reject) {
      if (!requestsVideo(constraints)) {
        callOrigGetUserMedia(constraints).then(resolve, reject);
        return;
      }
      resolveCameraMode().then(
        function (response) {
          const mode = response ? response.mode : 'real';
          if (mode === 'image') {
            createReplacedStream(constraints, response.image).then(
              function (stream) { resolve(stream); },
              function (e) { reject(toPageError(e)); }
            );
          } else if (mode === 'deny') {
            reject(pageDomException('Permission denied', 'NotAllowedError'));
          } else {
            callOrigGetUserMedia(constraints).then(resolve, reject);
          }
        },
        // ネイティブ側が応答できない場合は Gecko 本体の権限処理に任せる
        function () {
          callOrigGetUserMedia(constraints).then(resolve, reject);
        }
      );
    }, pageWin));
  }

  exportFunction(function (constraints) {
    return handleGetUserMedia(constraints);
  }, pageWin.MediaDevices.prototype, { defineAs: 'getUserMedia' });

  // 旧来のコールバック形式の API も同じ経路を通す
  if (typeof pageWin.navigator.mozGetUserMedia === 'function') {
    exportFunction(function (constraints, success, error) {
      handleGetUserMedia(constraints).then(success, error);
    }, pageWin.Navigator.prototype, { defineAs: 'mozGetUserMedia' });
  }
})();
