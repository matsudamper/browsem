const {test} = require("node:test");
const assert = require("node:assert/strict");
const {readFileSync} = require("node:fs");
const vm = require("node:vm");
const source = readFileSync(__dirname + "/../../main/assets/web_extensions/media_bridge/playback_policy.js", "utf8");

function fixture() {
  const elements = [];
  const listeners = new Map();
  const values = new WeakMap();
  const calls = {play: 0, audible: 0, blocked: 0};
  const messages = [];
  const timers = new Map();
  const mediaListeners = new WeakMap();
  let portListener;
  let portDisconnectListener;
  let failConnection = false;
  let now = 1;
  class NativeMedia {
    constructor() {
      values.set(this, {muted: false, volume: 1, src: "a", srcObject: null});
      this.currentSrc = "a";
      this.currentTime = 10;
      this.duration = 60;
      this.paused = true;
      this.ended = false;
      this.isConnected = true;
      this.attributes = {};
      elements.push(this);
    }
    play() {
      calls.play++;
      this.paused = false;
      if (!this.muted && this.volume > 0) calls.audible++;
      return Promise.resolve();
    }
    pause() { this.paused = true; }
    getAttribute(name) { return this.attributes[name] || null; }
    addEventListener(name, listener) {
      if (!mediaListeners.has(this)) mediaListeners.set(this, new Map());
      mediaListeners.get(this).set(name, listener);
    }
  }
  ["muted", "volume", "src", "srcObject"].forEach(function (name) {
    Object.defineProperty(NativeMedia.prototype, name, {
      configurable: true,
      get() { return values.get(this)[name]; },
      set(value) {
        values.get(this)[name] = value;
        if (name === "src") {
          this.currentTime = 0;
          this.duration = NaN;
        }
      },
    });
  });
  class PageMedia extends NativeMedia {}
  ["muted", "volume", "src", "srcObject"].forEach(function (name) {
    Object.defineProperty(PageMedia.prototype, name, Object.getOwnPropertyDescriptor(NativeMedia.prototype, name));
  });
  const navigator = {mediaSession: {metadata: null}};
  const context = vm.createContext({
    window: {
      wrappedJSObject: {HTMLMediaElement: PageMedia, Promise, DOMException},
      setTimeout: (callback, delay) => { const id = Symbol(); timers.set(id, {callback, at: now + delay}); return id; },
      clearTimeout: id => timers.delete(id),
      setInterval: () => {},
      addEventListener: () => {},
    },
    HTMLMediaElement: NativeMedia,
    navigator,
    crypto: {getRandomValues: array => array.fill(1)},
    performance: {now: () => now},
    document: {
      title: "X",
      visibilityState: "visible",
      querySelectorAll: () => elements,
      querySelector: () => null,
      addEventListener(name, listener) {
        if (!listeners.has(name)) listeners.set(name, new Set());
        listeners.get(name).add(listener);
      },
    },
    location: {href: "https://example.com"},
    MutationObserver: class { observe() {} },
    browser: {runtime: {connectNative: () => {
      if (failConnection) throw new Error("not registered");
      return {
        onMessage: {addListener: listener => portListener = listener},
        onDisconnect: {addListener: listener => portDisconnectListener = listener},
        postMessage: message => messages.push(message),
      };
    }}},
    exportFunction: fn => fn,
    XPCNativeWrapper: media => media,
  });
  vm.runInContext(source + "\nglobalThis.policy = backgroundPlaybackPolicy;", context);
  const policy = context.policy;
  policy.setBlockedListener(() => calls.blocked++);
  function flushTimers() {
    for (const [id, timer] of Array.from(timers)) {
      if (timer.at <= now) {
        timers.delete(id);
        timer.callback();
      }
    }
  }
  function event(name, media) {
    for (const listener of listeners.get(name) || []) listener({type: name, target: media});
  }
  return {
    policy, calls, navigator,
    media: () => new PageMedia(),
    foreground: () => policy.apply({type: "playbackPolicy", inBackground: false}),
    background: media => policy.apply({type: "playbackPolicy", inBackground: true, continuationId: media ? policy.identity(media) : "", activePlaybackId: media ? policy.identity(media) : ""}),
    allow: () => policy.apply({type: "allowPlayback", windowMs: 30000}),
    stop: () => policy.apply({type: "stopPlayback"}),
    advance: ms => { now += ms; flushTimers(); },
    event,
    visibility: value => { context.document.visibilityState = value; event("visibilitychange"); },
    nativePlay: media => NativeMedia.prototype.play.call(media),
    startContent: () => vm.runInContext(readFileSync(__dirname + "/../../main/assets/web_extensions/media_bridge/content.js", "utf8"), context),
    receive: message => portListener(message),
    relay: receiver => context.browser.runtime.sendMessage = receiver,
    disconnect: () => portDisconnectListener(),
    failConnection: value => failConnection = value,
    publish: () => {
      now += 120;
      flushTimers();
      return messages.filter(message => !message.type).at(-1);
    },
    messages,
  };
}

test("背景中の play はネイティブ再生に到達せず拒否する", async () => {
  const f = fixture();
  f.foreground();
  const media = f.media();
  f.background();
  await assert.rejects(media.play(), {name: "NotAllowedError"});
  assert.equal(f.calls.play, 0);
  assert.equal(f.calls.audible, 0);
});

test("ミュート自動再生からのアンミュートと音量変更は同期的に無音化する", async () => {
  const f = fixture();
  f.foreground();
  const media = f.media();
  media.muted = true;
  await media.play();
  f.background();
  media.muted = false;
  media.volume = 1;
  assert.equal(media.paused, true);
  assert.equal(media.muted, true);
  assert.equal(f.calls.audible, 0);
});

test("閉じた時点の再生はバッファリング後も継続する", async () => {
  const f = fixture();
  f.foreground();
  const media = f.media();
  await media.play();
  f.background(media);
  media.pause();
  await media.play();
  assert.equal(media.paused, false);
});

test("画質変更は再生位置と長さを復元した同じ要素に限って許す", async () => {
  const f = fixture();
  f.foreground();
  const media = f.media();
  await media.play();
  const id = f.policy.identity(media);
  f.background(media);
  media.src = "quality2";
  media.currentTime = 10;
  media.duration = 60;
  await media.play();
  assert.equal(f.policy.identity(media), id);
  assert.equal(media.muted, false);
});

test("同じタイトルでも別の要素や先頭からの次の動画は許さない", async () => {
  const f = fixture();
  f.foreground();
  f.navigator.mediaSession.metadata = {title: "X"};
  const media = f.media();
  await media.play();
  f.background(media);
  const next = f.media();
  await assert.rejects(next.play(), {name: "NotAllowedError"});
  media.src = "next";
  media.duration = 60;
  await assert.rejects(media.play(), {name: "NotAllowedError"});
});

test("通知の次へは古い再生で消費せず新しい一件だけを許す", async () => {
  const f = fixture();
  f.foreground();
  const media = f.media();
  await media.play();
  f.background(media);
  f.allow();
  await media.play();
  const next = f.media();
  await next.play();
  await assert.rejects(f.media().play(), {name: "NotAllowedError"});
});

test("通知の停止と許可期限切れのあとには再生を許さない", async () => {
  const f = fixture();
  f.foreground();
  const media = f.media();
  f.background();
  f.allow();
  f.stop();
  await assert.rejects(media.play(), {name: "NotAllowedError"});
  f.allow();
  f.advance(30001);
  await assert.rejects(media.play(), {name: "NotAllowedError"});
});

test("終了後の同じ URL の再開は通知操作だけに限る", async () => {
  const f = fixture();
  f.foreground();
  const media = f.media();
  await media.play();
  f.background(media);
  media.ended = true;
  f.event("ended", media);
  media.ended = false;
  await assert.rejects(media.play(), {name: "NotAllowedError"});
  f.allow();
  await media.play();
  assert.equal(media.muted, false);
});

test("状態受信前と新規フレームは許可が届くまで再生しない", async () => {
  const f = fixture();
  const media = f.media();
  const pending = media.play();
  assert.equal(f.calls.play, 0);
  f.background();
  await assert.rejects(pending, {name: "NotAllowedError"});
  f.foreground();
  await media.play();
  assert.equal(media.muted, false);
});

test("新しい autoplay 要素は監視時点で無音化する", async () => {
  const f = fixture();
  f.foreground();
  f.background();
  const media = f.media();
  f.policy.protect(media);
  await f.nativePlay(media);
  f.event("play", media);
  assert.equal(f.calls.audible, 0);
  assert.equal(media.paused, true);
});

test("通知の一時停止は通知対象を残し勝手な再開は許さない", async () => {
  const f = fixture();
  f.foreground();
  const media = f.media();
  await media.play();
  f.background(media);
  f.stop();
  assert.equal(f.policy.isRetained(media), true);
  assert.equal(f.policy.isAllowed(media), false);
  await assert.rejects(media.play(), {name: "NotAllowedError"});
  f.allow();
  await media.play();
});

test("終了の snapshot は元のIDを報告し再開時にはIDを変える", async () => {
  const f = fixture();
  f.foreground();
  const media = f.media();
  await media.play();
  const id = f.policy.identity(media);
  f.background(media);
  media.ended = true;
  f.event("ended", media);
  assert.equal(f.policy.identity(media), id);
  media.ended = false;
  assert.notEqual(f.policy.identity(media), id);
});

test("ポート切断時は前面扱いを解除して無音化する", async () => {
  const f = fixture();
  f.foreground();
  const media = f.media();
  await media.play();
  f.policy.apply({type: "playbackPolicy", inBackground: true});
  f.stop();
  assert.equal(media.muted, true);
  assert.equal(media.paused, true);
  await assert.rejects(media.play(), {name: "NotAllowedError"});
});

test("遮断後はページの playbackState が playing でも通知対象にしない", async () => {
  const f = fixture();
  const media = f.media();
  f.startContent();
  f.receive({type: "playbackPolicy", inBackground: false});
  media.muted = true;
  await media.play();
  f.receive({type: "playbackPolicy", inBackground: true});
  f.navigator.mediaSession.playbackState = "playing";
  media.muted = false;
  const snapshot = f.publish();
  assert.equal(snapshot.isPlaying, false);
  assert.equal(snapshot.isActive, false);
  assert.equal(f.calls.audible, 0);
});

test("通知の停止後は一時停止状態を公開して再生ボタンを維持する", async () => {
  const f = fixture();
  const media = f.media();
  f.startContent();
  f.receive({type: "playbackPolicy", inBackground: false});
  await media.play();
  f.receive({type: "playbackPolicy", inBackground: true, continuationId: f.policy.identity(media)});
  f.receive({type: "stopPlayback"});
  const snapshot = f.publish();
  assert.equal(snapshot.isPlaying, false);
  assert.equal(snapshot.isActive, true);
});

test("ページ側の許可を反映してから操作準備完了を返す", async () => {
  const f = fixture();
  const media = f.media();
  f.startContent();
  f.receive({type: "playbackPolicy", inBackground: true});
  f.receive({type: "allowPlayback", commandId: 7, windowMs: 30000});
  assert.equal(f.messages.at(-1).type, "policyReady");
  assert.equal(f.messages.at(-1).commandId, 7);
  await media.play();
  await assert.rejects(f.media().play(), {name: "NotAllowedError"});
});

test("初期接続に失敗しても無音のまま再接続し前面の再生を復旧する", async () => {
  const f = fixture();
  const media = f.media();
  f.failConnection(true);
  f.startContent();
  const pending = media.play();
  assert.equal(f.calls.play, 0);
  f.failConnection(false);
  f.advance(1000);
  f.publish();
  f.receive({type: "playbackPolicy", inBackground: false});
  await pending;
  assert.equal(media.paused, false);
  assert.equal(media.muted, false);
});

test("接続が切れたあとは再接続しても背景許可を勝手に復元しない", async () => {
  const f = fixture();
  const media = f.media();
  f.startContent();
  f.receive({type: "playbackPolicy", inBackground: false});
  await media.play();
  f.disconnect();
  assert.equal(media.paused, true);
  assert.equal(media.muted, true);
  f.advance(1000);
  f.publish();
  f.receive({type: "playbackPolicy", inBackground: true});
  await assert.rejects(media.play(), {name: "NotAllowedError"});
});

test("未登録の visible セッションは250msで保留した再生を解放する", async () => {
  const f = fixture();
  const media = f.media();
  const pending = media.play();
  f.advance(249);
  assert.equal(f.calls.play, 0);
  f.advance(1);
  await pending;
  assert.equal(f.calls.play, 1);
  assert.equal(f.policy.state().visibleFallback, true);
});

test("未応答の hidden セッションは解放せず visible に戻ってから再生する", async () => {
  const f = fixture();
  f.visibility("hidden");
  const media = f.media();
  const pending = media.play();
  f.advance(1000);
  assert.equal(f.calls.play, 0);
  f.visibility("visible");
  await pending;
  f.visibility("hidden");
  assert.equal(media.muted, true);
  assert.equal(media.paused, true);
  await assert.rejects(media.play(), {name: "NotAllowedError"});
});

test("visibleでもネイティブの背景指定と指定後の切断にはフォールバックしない", async () => {
  const f = fixture();
  const media = f.media();
  f.background();
  f.advance(1000);
  f.visibility("visible");
  f.policy.connectionLost();
  assert.equal(f.policy.state().visibleFallback, false);
  await assert.rejects(media.play(), {name: "NotAllowedError"});
});

test("フォールバック後に届いた背景指定で直ちに無音化する", async () => {
  const f = fixture();
  const media = f.media();
  f.advance(250);
  await media.play();
  f.background();
  assert.equal(media.paused, true);
  assert.equal(media.muted, true);
});

test("初回の前面指定が届いたら250msを待たず保留とautoplayを復旧する", async () => {
  const f = fixture();
  const media = f.media();
  const pending = media.play();
  f.advance(5);
  f.foreground();
  await pending;
  assert.equal(f.calls.play, 1);
  const initial = fixture();
  const initialMedia = initial.media();
  await initial.nativePlay(initialMedia);
  initial.policy.protect(initialMedia);
  assert.equal(initialMedia.paused, true);
  initial.foreground();
  assert.equal(initialMedia.paused, false);
  assert.equal(initialMedia.muted, false);
});

test("背景で許可したページのplayには操作元とidentityを記録する", async () => {
  const f = fixture();
  const media = f.media();
  f.startContent();
  f.receive({type: "playbackPolicy", inBackground: false});
  await media.play();
  f.receive({type: "playbackPolicy", inBackground: true, continuationId: f.policy.identity(media)});
  await media.play();
  const log = f.messages.find(message => message.event === "ページ許可");
  assert.equal(log.origin, "ページの play()");
  assert.equal(log.identity, f.policy.identity(media));
  assert.match(log.reason, /identityに一致/);
});

test("アンミュートの同期拒否には実際の停止経路を記録する", async () => {
  const f = fixture();
  const media = f.media();
  f.startContent();
  f.receive({type: "playbackPolicy", inBackground: false});
  media.muted = true;
  await media.play();
  f.receive({type: "playbackPolicy", inBackground: true});
  media.muted = false;
  const log = f.messages.find(message => message.type === "blocked" && message.origin === "アンミュート");
  assert.equal(log.origin, "アンミュート");
  assert.equal(log.stopPath, "content script: muted=true,pause()");
});

test("未接続中のフォールバックと接続失敗は重複を集計して復旧時に送る", async () => {
  const f = fixture();
  f.failConnection(true);
  f.startContent();
  f.advance(1000);
  f.advance(1000);
  f.failConnection(false);
  f.advance(1000);
  f.receive({type: "playbackPolicy", inBackground: false});
  assert.equal(f.messages.some(message => message.event === "フォールバック"), true);
  const failed = f.messages.find(message => message.reason === "接続失敗");
  assert.equal(failed.suppressedBeforeConnect, 2);
  assert.equal(f.messages.some(message => message.reason === "再接続"), true);
});

test("コマンド取消しは継続中の再生を止めず次の再生だけを拒否する", async () => {
  const f = fixture();
  f.foreground();
  const media = f.media();
  await media.play();
  f.background(media);
  f.allow();
  f.policy.apply({type: "cancelPlaybackCommand"});
  assert.equal(media.paused, false);
  await assert.rejects(f.media().play(), {name: "NotAllowedError"});
});

test("セッションのポートが未登録でもフォールバック診断をbackgroundへ送る", async () => {
  const f = fixture();
  const relayed = [];
  f.failConnection(true);
  f.relay(message => { relayed.push(message); return Promise.resolve(true); });
  f.startContent();
  const pending = f.media().play();
  f.advance(250);
  await pending;
  assert.equal(relayed.some(message => message.payload.event === "フォールバック"), true);
  assert.equal(relayed.at(-1).payload.pageUrl, "https://example.com");
});

test("診断中継はネイティブ準備完了まで保持し件数制限と抑制件数を維持する", async () => {
  const messages = [];
  let receivePage;
  let nativeReady;
  const context = vm.createContext({
    browser: {runtime: {
      connectNative: () => ({
        onMessage: {addListener: listener => nativeReady = listener},
        onDisconnect: {addListener: () => {}},
        postMessage: message => messages.push(message),
      }),
      onMessage: {addListener: listener => receivePage = listener},
    }},
    setTimeout: () => {},
  });
  vm.runInContext(readFileSync(__dirname + "/../../main/assets/web_extensions/media_bridge/diagnostics.js", "utf8"), context);
  for (let i = 0; i < 40; i++) {
    await receivePage({type: "mediaDiagnostic", payload: {event: "遮断", identity: String(i), origin: "play", reason: "背景"}});
  }
  await receivePage({type: "mediaDiagnostic", payload: {event: "遮断", identity: "39", origin: "play", reason: "背景"}});
  assert.equal(messages.length, 0);
  nativeReady({type: "diagnosticsReady"});
  assert.equal(messages.length, 32);
  assert.equal(messages[0].relayDropped, 8);
  assert.equal(messages.at(-1).suppressedBeforeConnect, 1);
});

test("ポートオブジェクトだけがあり初回応答が来ない場合も診断を中継する", async () => {
  const f = fixture();
  const relayed = [];
  f.relay(message => { relayed.push(message); return Promise.resolve(true); });
  f.startContent();
  f.advance(250);
  assert.equal(relayed.some(message => message.payload.event === "フォールバック"), true);
  assert.equal(f.messages.some(message => message.event === "フォールバック"), false);
});

test("停止中の要素の音量設定では通知操作の許可を消費しない", async () => {
  const f = fixture();
  f.foreground();
  const media = f.media();
  await media.play();
  f.background(media);
  f.stop();
  f.allow();
  media.volume = 0.5;
  f.event("volumechange", media);
  media.src = "next-track";
  await media.play();
  assert.equal(media.paused, false);
  assert.equal(media.muted, false);
});
