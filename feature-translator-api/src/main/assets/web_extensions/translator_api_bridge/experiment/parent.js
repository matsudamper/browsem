// Gecko 内蔵の翻訳エンジン（ページ翻訳と同じもの）と言語判定を使う experiment API。
// GeckoView はテキスト単位の翻訳 API を公開していないため、親プロセスの TranslationsParent を直接使う。
/* global ExtensionAPI, ChromeUtils */
"use strict";

const lazy = {};
ChromeUtils.defineESModuleGetters(lazy, {
  LanguageDetector: "resource://gre/modules/translations/LanguageDetector.sys.mjs",
  TranslationsParent: "resource://gre/actors/TranslationsParent.sys.mjs",
  clearTimeout: "resource://gre/modules/Timer.sys.mjs",
  setTimeout: "resource://gre/modules/Timer.sys.mjs",
});

// 翻訳エンジンとの接続。言語ペアごとに持ち、エンジンが終了を通知してきたら捨てる。
// エンジンは使われないまま一定時間が経つと自ら終了するため、こちらでは寿命を管理しない
const connections = new Map();

// エンジンは推論に失敗しても応答を返さないため、待ち続けないよう期限を設ける
const TRANSLATION_TIMEOUT_MS = 60000;

function unsupported() {
  return {
    success: false,
    errorName: "NotSupportedError",
    error: "この言語の組み合わせは翻訳できません",
  };
}

function failed(error) {
  return {
    success: false,
    errorName: "OperationError",
    error: (error && error.message) || "翻訳に失敗しました",
  };
}

// 翻訳エンジンが扱える言語タグの組へ解決する。対応していなければ null
async function resolveLanguagePair(sourceLanguage, targetLanguage) {
  if (!lazy.TranslationsParent.getIsTranslationsEngineSupported()) return null;
  const source = await lazy.TranslationsParent.findCompatibleSourceLangTag(sourceLanguage);
  const target = await lazy.TranslationsParent.findCompatibleTargetLangTag(targetLanguage);
  if (!source || !target || source === target) return null;
  return { sourceLanguage: source, targetLanguage: target };
}

function closeConnection(connection, error) {
  if (connection.closed) return;
  connection.closed = true;
  if (connections.get(connection.key) === connection) {
    connections.delete(connection.key);
  }
  connection.rejectReady(error);
  for (const request of connection.pendingRequests.values()) {
    lazy.clearTimeout(request.timeoutId);
    request.reject(error);
  }
  connection.pendingRequests.clear();
  closePort(connection.port);
}

function closePort(port) {
  if (!port) return;
  try {
    port.postMessage({ type: "TranslationsPort:Close" });
  } catch (_error) {
    // 既に閉じられている
  }
  port.close();
}

async function openConnection(connection, languagePair) {
  const port = await lazy.TranslationsParent.requestTranslationsPort(languagePair);
  if (!port) throw new Error("翻訳エンジンへ接続できませんでした");
  if (connection.closed) {
    // ポートの取得を待つ間に接続が閉じられた。取得したポートを残さない
    closePort(port);
    return;
  }
  connection.port = port;
  port.onmessage = function ({ data }) {
    switch (data.type) {
      case "TranslationsPort:GetEngineStatusResponse":
        if (data.status === "ready") {
          connection.resolveReady();
        } else {
          closeConnection(
            connection,
            new Error(data.error || "翻訳エンジンを準備できませんでした"),
          );
        }
        break;
      case "TranslationsPort:TranslationResponse": {
        const request = connection.pendingRequests.get(data.translationId);
        if (request) {
          connection.pendingRequests.delete(data.translationId);
          lazy.clearTimeout(request.timeoutId);
          request.resolve(data.targetText);
        }
        break;
      }
      case "TranslationsPort:EngineTerminated":
        closeConnection(connection, new Error("翻訳エンジンが終了しました"));
        break;
    }
  };
  port.postMessage({ type: "TranslationsPort:GetEngineStatusRequest" });
}

// 接続の準備（必要ならモデルのダウンロード）が終わるまで待って返す
async function connect(languagePair) {
  const key = languagePair.sourceLanguage + "," + languagePair.targetLanguage;
  let connection = connections.get(key);
  if (!connection) {
    connection = {
      key: key,
      port: null,
      closed: false,
      nextTranslationId: 0,
      pendingRequests: new Map(),
    };
    connection.ready = new Promise(function (resolve, reject) {
      connection.resolveReady = resolve;
      connection.rejectReady = reject;
    });
    // 誰も待っていない間に閉じられても、未処理の reject として報告されないようにする
    connection.ready.catch(function () {});
    connections.set(key, connection);
    const opening = connection;
    openConnection(opening, languagePair).catch(function (error) {
      closeConnection(opening, error);
    });
  }
  await connection.ready;
  return connection;
}

// モデルのダウンロードはユーザー操作を確認済みの prepare だけが行う。
// translate で新しく接続するとモデルを取得してしまうため、取得済みかを先に確かめる
async function isReadyWithoutDownload(languagePair) {
  const key = languagePair.sourceLanguage + "," + languagePair.targetLanguage;
  if (connections.has(key)) return true;
  const downloadSize = await lazy.TranslationsParent.getExpectedTranslationDownloadSize(
    languagePair.sourceLanguage,
    languagePair.targetLanguage,
  );
  return !(downloadSize > 0);
}

function requestTranslation(connection, text) {
  return new Promise(function (resolve, reject) {
    if (connection.closed) {
      reject(new Error("翻訳エンジンとの接続が閉じられました"));
      return;
    }
    const translationId = ++connection.nextTranslationId;
    const timeoutId = lazy.setTimeout(function () {
      connection.pendingRequests.delete(translationId);
      reject(new Error("翻訳が時間内に完了しませんでした"));
    }, TRANSLATION_TIMEOUT_MS);
    connection.pendingRequests.set(translationId, {
      resolve: resolve,
      reject: reject,
      timeoutId: timeoutId,
    });
    connection.port.postMessage({
      type: "TranslationsPort:TranslationRequest",
      translationId: translationId,
      sourceText: text,
      isHTML: false,
    });
  });
}

this.browsemTranslation = class extends ExtensionAPI {
  getAPI() {
    return {
      browsemTranslation: {
        async availability(sourceLanguage, targetLanguage) {
          try {
            const languagePair = await resolveLanguagePair(sourceLanguage, targetLanguage);
            if (!languagePair) return { success: true, availability: "unavailable" };
            const downloadSize = await lazy.TranslationsParent.getExpectedTranslationDownloadSize(
              languagePair.sourceLanguage,
              languagePair.targetLanguage,
            );
            return {
              success: true,
              availability: downloadSize > 0 ? "downloadable" : "available",
            };
          } catch (error) {
            return failed(error);
          }
        },

        async prepare(sourceLanguage, targetLanguage) {
          try {
            const languagePair = await resolveLanguagePair(sourceLanguage, targetLanguage);
            if (!languagePair) return unsupported();
            await connect(languagePair);
            return { success: true };
          } catch (error) {
            return failed(error);
          }
        },

        async translate(sourceLanguage, targetLanguage, text) {
          try {
            const languagePair = await resolveLanguagePair(sourceLanguage, targetLanguage);
            if (!languagePair) return unsupported();
            if (!(await isReadyWithoutDownload(languagePair))) {
              return {
                success: false,
                errorName: "NotAllowedError",
                error: "翻訳モデルが準備されていません",
              };
            }
            const connection = await connect(languagePair);
            return { success: true, text: await requestTranslation(connection, text) };
          } catch (error) {
            return failed(error);
          }
        },

        async detectLanguage(text) {
          try {
            const detection = await lazy.LanguageDetector.detectLanguage(text);
            return {
              success: true,
              languages: detection.languages.map(function (language) {
                return {
                  detectedLanguage: language.languageCode,
                  confidence: language.percent / 100,
                };
              }),
            };
          } catch (error) {
            return failed(error);
          }
        },
      },
    };
  }

  onShutdown() {
    for (const connection of Array.from(connections.values())) {
      closeConnection(connection, new Error("拡張機能が終了しました"));
    }
  }
};
