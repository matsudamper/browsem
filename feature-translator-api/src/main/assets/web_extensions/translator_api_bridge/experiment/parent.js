// Gecko 内蔵の翻訳エンジン（ページ翻訳と同じもの）でテキストを翻訳する experiment API。
// GeckoView はテキスト単位の翻訳 API を公開していないため、親プロセスの TranslationsParent を直接使う。
/* global ExtensionAPI, ChromeUtils */
"use strict";

const lazy = {};
ChromeUtils.defineESModuleGetters(lazy, {
  TranslationsParent: "resource://gre/actors/TranslationsParent.sys.mjs",
});

// 翻訳エンジンとの接続。直近に使った 1 言語ペア分だけを保持する
let activeConnection = null;

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
  for (const request of connection.pendingRequests.values()) {
    request.reject(error);
  }
  connection.pendingRequests.clear();
  if (connection.port) {
    try {
      connection.port.postMessage({ type: "TranslationsPort:Close" });
    } catch (_error) {
      // 既に閉じられている
    }
    connection.port.close();
  }
}

async function openConnection(connection, languagePair) {
  const port = await lazy.TranslationsParent.requestTranslationsPort(languagePair);
  if (!port) throw new Error("翻訳エンジンへ接続できませんでした");
  connection.port = port;
  await new Promise(function (resolve, reject) {
    port.onmessage = function ({ data }) {
      switch (data.type) {
        case "TranslationsPort:GetEngineStatusResponse":
          if (data.status === "ready") {
            resolve();
          } else {
            reject(new Error(data.error || "翻訳エンジンを準備できませんでした"));
          }
          break;
        case "TranslationsPort:TranslationResponse": {
          const request = connection.pendingRequests.get(data.translationId);
          if (request) {
            connection.pendingRequests.delete(data.translationId);
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
  });
}

// 接続の準備（必要ならモデルのダウンロード）が終わるまで待って返す
async function connect(languagePair) {
  const key = languagePair.sourceLanguage + "," + languagePair.targetLanguage;
  if (!activeConnection || activeConnection.closed || activeConnection.key !== key) {
    if (activeConnection) {
      closeConnection(activeConnection, new Error("別の言語の翻訳に切り替わりました"));
    }
    const connection = {
      key: key,
      port: null,
      closed: false,
      nextTranslationId: 0,
      pendingRequests: new Map(),
      ready: null,
    };
    connection.ready = openConnection(connection, languagePair).catch(function (error) {
      closeConnection(connection, error);
      throw error;
    });
    activeConnection = connection;
  }
  const connection = activeConnection;
  await connection.ready;
  if (connection.closed) throw new Error("翻訳エンジンとの接続が閉じられました");
  return connection;
}

function requestTranslation(connection, text) {
  return new Promise(function (resolve, reject) {
    const translationId = ++connection.nextTranslationId;
    connection.pendingRequests.set(translationId, { resolve: resolve, reject: reject });
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
            const connection = await connect(languagePair);
            return { success: true, text: await requestTranslation(connection, text) };
          } catch (error) {
            return failed(error);
          }
        },
      },
    };
  }

  onShutdown() {
    if (activeConnection) {
      closeConnection(activeConnection, new Error("拡張機能が終了しました"));
      activeConnection = null;
    }
  }
};
