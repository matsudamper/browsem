// Translator and Language Detector APIs
// (https://developer.mozilla.org/en-US/docs/Web/API/Translator_and_Language_Detector_APIs) のポリフィル。
// Gecko が未実装の間、ページコンテキストへ Translator と LanguageDetector を exportFunction で公開し、
// 翻訳と言語判定はブラウザに設定された翻訳プロバイダへ委譲する。
(function () {
  "use strict";

  const NATIVE_APP = "translatorApiBridge";
  // ネイティブ側の MAX_INPUT_CHARS と一致させる
  const INPUT_QUOTA = 100000;

  const pageWin = window.wrappedJSObject;
  // 仕様上 [SecureContext] のため、非セキュアコンテキストには公開しない
  if (!window.isSecureContext) return;

  // Xray 越しでは関数値のプロパティが見えないため、ページ由来のオブジェクトは waive して読む
  function waive(value) {
    return (value && value.wrappedJSObject) || value;
  }

  // pageWin 経由の値はページが書き換えられるため、モデルのダウンロード可否の判定には Xray 越しの値を使う
  function hasUserActivation() {
    return window.navigator.userActivation.isActive;
  }

  function pageDomException(message, name) {
    return new pageWin.DOMException(message, name);
  }

  // content script 側の Promise はページから then できないため、ページコンパートメントの Promise で返す。
  // executor の reject にはページコンパートメントのエラーを渡すこと。
  function pagePromise(executor) {
    return new pageWin.Promise(exportFunction(executor, pageWin));
  }

  function toPageError(error) {
    // 中断理由には null や 0 も指定できるため、値の真偽ではなくプロパティの有無で判定する
    if (error && typeof error === "object" && "pageError" in error) return error.pageError;
    return pageDomException(
      (error && error.message) || "翻訳に失敗しました",
      (error && error.name) || "OperationError",
    );
  }

  function failure(pageError) {
    return { pageError: pageError };
  }

  // 翻訳プロバイダの設定はネイティブアプリが持つため、まずネイティブへ尋ねる。
  // プロバイダが Gecko のときは、Gecko 内蔵の翻訳エンジンを呼べるバックグラウンドへ回すよう返ってくる。
  // これより長くかかった要求、失敗した要求、迂回した要求をアプリのログへ残す
  const SLOW_REQUEST_MS = 1000;
  // 応答が返らないまま止まると完了時の記録が残らないため、待ちが長引いた時点でも記録する
  const PENDING_REPORT_MS = 5000;

  function requestTranslationEngine(message) {
    const trace = { startedAt: Date.now(), steps: [], detoured: false };
    const pendingTimer = setTimeout(function () {
      reportToApp("Translator API 応答待ち", describeRequest(message, trace, "応答待ち"));
    }, PENDING_REPORT_MS);

    return sendToNativeApp(message, trace)
      .then(
        function (response) {
          return response && response.useGeckoEngine ? sendToGeckoEngine(message, trace) : response;
        },
        function (errors) {
          // アプリに届かないと設定された翻訳プロバイダが分からない。機能ごと使えなくするより、
          // アプリを介さずに動く Gecko 内蔵の翻訳エンジンで処理する
          trace.detoured = true;
          console.error("translator-api: アプリと通信できないため Gecko 内蔵エンジンを使う", errors.map(describeError));
          return sendToGeckoEngine(message, trace);
        },
      )
      .then(
        function (response) {
          clearTimeout(pendingTimer);
          const failed = !response || !response.success;
          const outcome = failed
            ? "失敗 " + ((response && response.errorName) || "") + " " + ((response && response.error) || "")
            : "成功";
          if (failed || trace.detoured || message.action === "create" || elapsedMs(trace) >= SLOW_REQUEST_MS) {
            reportToApp(
              failed ? "Translator API 失敗" : trace.detoured ? "Translator API 迂回" : "Translator API 要求",
              describeRequest(message, trace, outcome),
            );
          }
          return response;
        },
        function (error) {
          clearTimeout(pendingTimer);
          reportToApp("Translator API 失敗", describeRequest(message, trace, "失敗 " + describeError(error)));
          throw error;
        },
      );
  }

  function elapsedMs(trace) {
    return Date.now() - trace.startedAt;
  }

  // 本文は記録せず、長さだけを残す
  function describeRequest(message, trace, outcome) {
    const lines = ["action=" + message.action];
    if (message.sourceLanguage) lines.push("languages=" + message.sourceLanguage + "->" + message.targetLanguage);
    if (typeof message.text === "string") lines.push("textLength=" + message.text.length);
    lines.push("totalMs=" + elapsedMs(trace));
    lines.push("outcome=" + outcome);
    lines.push("page=" + location.origin);
    return lines.concat(trace.steps).join("\n");
  }

  // 経路ごとの所要時間と、応答に含まれる内訳を trace へ積む
  function timed(trace, name, promise) {
    const startedAt = Date.now();
    return promise.then(
      function (response) {
        const parts = [name + "=" + (Date.now() - startedAt) + "ms"];
        if (response && response.engine) parts.push("engine=" + response.engine);
        if (response && response.appMs !== undefined) parts.push("appMs=" + response.appMs);
        if (response && response.timing) parts.push("timing=" + JSON.stringify(response.timing));
        trace.steps.push(parts.join(" "));
        return response;
      },
      function (error) {
        trace.steps.push(name + "=" + (Date.now() - startedAt) + "ms failed(" + describeError(error) + ")");
        throw error;
      },
    );
  }

  function sendToGeckoEngine(message, trace) {
    return timed(trace, "gecko", browser.runtime.sendMessage(message)).catch(function (error) {
      const detail = describeError(error);
      console.error("translator-api: 拡張機能のバックグラウンドとの通信に失敗", detail);
      throw failure(
        pageDomException("拡張機能のバックグラウンドとの通信に失敗しました (" + detail + ")", "OperationError"),
      );
    });
  }

  let directNativeMessagingFailed = false;

  // content script からネイティブアプリへの直接通信は Gecko 側で拒否されることがあり、
  // その場合は原因を問わず同じ定型文のエラーになる。バックグラウンド経由で送り直す。
  // どちらも失敗したら、各経路のエラーの配列で reject する
  function sendToNativeApp(message, trace) {
    if (directNativeMessagingFailed) {
      return relayToNativeApp(message, trace, []);
    }
    return timed(
      trace,
      "app-direct",
      Promise.resolve().then(function () {
        return browser.runtime.sendNativeMessage(NATIVE_APP, message);
      }),
    ).catch(function (directError) {
      console.warn("translator-api: ネイティブアプリへ直接送れなかったため中継する", describeError(directError));
      directNativeMessagingFailed = true;
      trace.detoured = true;
      return relayToNativeApp(message, trace, [directError]);
    });
  }

  function relayToNativeApp(message, trace, earlierErrors) {
    return timed(trace, "app-relay", browser.runtime.sendMessage({ relayToNativeApp: message })).catch(
      function (relayError) {
        throw earlierErrors.concat([relayError]);
      },
    );
  }

  // アプリのログ画面へ残す。記録の失敗で本来の処理を止めないよう、結果は待たず失敗も捨てる。
  // 直接の通信が止まっている場合でも残せるよう、バックグラウンド経由を先に試す
  function reportToApp(title, body) {
    const logMessage = { action: "log", title: title, body: body };
    browser.runtime
      .sendMessage({ relayToNativeApp: logMessage })
      .catch(function () {
        return browser.runtime.sendNativeMessage(NATIVE_APP, logMessage);
      })
      .catch(function (error) {
        console.error("translator-api: アプリのログへ記録できなかった", describeError(error), title, body);
      });
  }

  function describeError(error) {
    return (error && error.message) || String(error);
  }

  function sendToEngine(message) {
    return requestTranslationEngine(message).then(function (response) {
      const result = response || {};
      if (!result.success) {
        throw failure(
          pageDomException(result.error || "翻訳に失敗しました", result.errorName || "OperationError"),
        );
      }
      return result;
    });
  }

  function canonicalLanguageTag(value, name) {
    if (value === undefined || value === null) {
      throw failure(new pageWin.TypeError(name + " is required"));
    }
    try {
      return Intl.getCanonicalLocales(String(value))[0];
    } catch (_error) {
      throw failure(new pageWin.RangeError("Invalid language tag: " + String(value)));
    }
  }

  function readLanguagePair(options) {
    const waived = waive(options);
    if (waived === undefined || waived === null || typeof waived !== "object") {
      throw failure(new pageWin.TypeError("options is required"));
    }
    return {
      sourceLanguage: canonicalLanguageTag(waived.sourceLanguage, "sourceLanguage"),
      targetLanguage: canonicalLanguageTag(waived.targetLanguage, "targetLanguage"),
    };
  }

  function readSignal(options) {
    const waived = waive(options);
    if (!waived || typeof waived !== "object") return null;
    if (waived.signal === undefined || waived.signal === null) return null;
    // waive したままだと content script の関数をリスナーとして登録できないため、Xray へ戻して扱う
    const signal = XPCNativeWrapper(waived.signal);
    if (!(signal instanceof AbortSignal)) {
      throw failure(new pageWin.TypeError("signal must be an AbortSignal"));
    }
    return signal;
  }

  // 既定の中断理由 (AbortError) を Xray 越しに読むと content script 側のオブジェクトになり、
  // ページの Promise へ渡せない。waive してページ側の値として読む
  function abortReason(signal) {
    return waive(signal).reason;
  }

  function throwIfAborted(signals) {
    for (let i = 0; i < signals.length; i++) {
      if (signals[i].aborted) throw failure(abortReason(signals[i]));
    }
  }

  // 中断済みなら start を呼ばずに reject する。開始後に中断された場合は reject するが、
  // 翻訳エンジン側の処理は止めず、結果を捨てる
  function abortable(start, signals) {
    return new Promise(function (resolve, reject) {
      throwIfAborted(signals);
      const listeners = signals.map(function (signal) {
        const listener = function () {
          settle(reject, failure(abortReason(signal)));
        };
        signal.addEventListener("abort", listener);
        return listener;
      });
      // 同じ signal を使い回されてもリスナーが溜まらないよう、終了時に必ず外す
      function settle(complete, value) {
        signals.forEach(function (signal, index) {
          signal.removeEventListener("abort", listeners[index]);
        });
        complete(value);
      }
      let started;
      try {
        started = start();
      } catch (error) {
        settle(reject, error);
        return;
      }
      started.then(
        function (value) {
          settle(resolve, value);
        },
        function (error) {
          settle(reject, error);
        },
      );
    });
  }

  // 仕様どおり、作成時に渡された signal の中断は作成後のインスタンスの破棄としても扱う
  function createDestroyController(creationSignal) {
    const destroyController = new AbortController();
    if (creationSignal) {
      creationSignal.addEventListener(
        "abort",
        function () {
          destroyController.abort(abortReason(creationSignal));
        },
        { once: true },
      );
    }
    return destroyController;
  }

  function dispatchDownloadProgress(monitorTarget, loaded) {
    monitorTarget.dispatchEvent(
      new pageWin.ProgressEvent(
        "downloadprogress",
        cloneInto({ loaded: loaded, total: 1, lengthComputable: true }, pageWin),
      ),
    );
  }

  function defineReadOnly(target, name, value) {
    pageWin.Object.defineProperty(
      target,
      name,
      cloneInto({ value: value, enumerable: true, writable: false, configurable: true }, pageWin),
    );
  }

  // new できないインターフェースとしてページへ公開し、ページ側のコンストラクタ関数を返す
  function definePageInterface(name, prototype) {
    pageWin.Object.defineProperty(
      pageWin,
      name,
      cloneInto({ enumerable: false, writable: true, configurable: true }, pageWin),
    );
    pageWin[name] = exportFunction(function () {
      throw new pageWin.TypeError("Illegal constructor");
    }, pageWin);
    const pageConstructor = pageWin[name];
    pageConstructor.prototype = prototype;
    pageWin.Object.defineProperty(
      prototype,
      "constructor",
      cloneInto({ enumerable: false, writable: true, configurable: true }, pageWin),
    );
    prototype.constructor = pageConstructor;
    return pageConstructor;
  }

  function readExpectedInputLanguages(options) {
    const waived = waive(options);
    if (waived === undefined || waived === null) return null;
    if (typeof waived !== "object") {
      throw failure(new pageWin.TypeError("options must be an object"));
    }
    const expected = waived.expectedInputLanguages;
    if (expected === undefined || expected === null) return null;
    const languageTags = [];
    for (let i = 0; i < expected.length; i++) {
      languageTags.push(canonicalLanguageTag(expected[i], "expectedInputLanguages"));
    }
    return languageTags;
  }

  // 判定できた言語を確度の高い順に並べ、仕様どおり末尾へ「判定不能」の und を置く
  function toDetectionResults(languages) {
    const detected = [];
    let totalConfidence = 0;
    for (let i = 0; i < languages.length; i++) {
      const languageTag = String(languages[i].detectedLanguage);
      const confidence = Number(languages[i].confidence);
      if (languageTag === "und" || languageTag === "un" || !(confidence > 0)) continue;
      detected.push({ detectedLanguage: languageTag, confidence: Math.min(confidence, 1) });
      totalConfidence += confidence;
    }
    detected.sort(function (left, right) {
      return right.confidence - left.confidence;
    });
    detected.push({ detectedLanguage: "und", confidence: Math.max(0, 1 - totalConfidence) });
    return detected;
  }

  function installLanguageDetector() {
    const detectorPrototype = new pageWin.Object();

    function createDetectorInstance(expectedInputLanguages, creationSignal) {
      const destroyController = createDestroyController(creationSignal);

      function detectLanguages(input, options) {
        const signals = [destroyController.signal];
        const signal = readSignal(options);
        if (signal) signals.push(signal);
        if (destroyController.signal.aborted) {
          return Promise.reject(
            failure(pageDomException("LanguageDetector は破棄されています", "InvalidStateError")),
          );
        }
        const text = String(input);
        if (text.length > INPUT_QUOTA) {
          return Promise.reject(
            failure(pageDomException("入力が長すぎます", "QuotaExceededError")),
          );
        }
        return abortable(function () {
          return sendToEngine({ action: "detectLanguage", text: text }).then(function (result) {
            return toDetectionResults(result.languages || []);
          });
        }, signals);
      }

      const instance = pageWin.Object.create(detectorPrototype);
      defineReadOnly(instance, "expectedInputLanguages", expectedInputLanguages);
      if (expectedInputLanguages !== null) {
        pageWin.Object.freeze(instance.expectedInputLanguages);
      }
      defineReadOnly(instance, "inputQuota", INPUT_QUOTA);

      instance.detect = exportFunction(function (input, options) {
        return pagePromise(function (resolve, reject) {
          let detection;
          try {
            detection = detectLanguages(input, options);
          } catch (error) {
            reject(toPageError(error));
            return;
          }
          detection.then(
            function (results) {
              resolve(cloneInto(results, pageWin));
            },
            function (error) {
              reject(toPageError(error));
            },
          );
        });
      }, pageWin);

      instance.measureInputUsage = exportFunction(function (input, options) {
        return pagePromise(function (resolve, reject) {
          try {
            const signal = readSignal(options);
            if (destroyController.signal.aborted) {
              reject(pageDomException("LanguageDetector は破棄されています", "InvalidStateError"));
            } else if (signal && signal.aborted) {
              reject(abortReason(signal));
            } else {
              resolve(String(input).length);
            }
          } catch (error) {
            reject(toPageError(error));
          }
        });
      }, pageWin);

      instance.destroy = exportFunction(function () {
        destroyController.abort(pageDomException("LanguageDetector は破棄されました", "AbortError"));
      }, pageWin);

      return instance;
    }

    function requestAvailability() {
      return sendToEngine({ action: "languageDetectorAvailability" }).then(function (result) {
        return String(result.availability);
      });
    }

    function createDetector(options) {
      const expectedInputLanguages = readExpectedInputLanguages(options);
      const signal = readSignal(options);
      const waivedOptions = waive(options);
      const monitor = waivedOptions ? waivedOptions.monitor : undefined;
      if (monitor !== undefined && monitor !== null && typeof monitor !== "function") {
        throw failure(new pageWin.TypeError("monitor must be a function"));
      }
      const monitorTarget = new pageWin.EventTarget();
      if (monitor) monitor(monitorTarget);

      return abortable(requestAvailability, signal ? [signal] : []).then(function (availability) {
        if (availability === "unavailable") {
          throw failure(pageDomException("言語を判定できません", "NotSupportedError"));
        }
        // 言語判定のモデルは同梱されておりダウンロードが起きないため、開始と完了を続けて伝える
        // 進捗イベントの中で中断されたら、以降のイベントもインスタンスも渡さない
        const signals = signal ? [signal] : [];
        dispatchDownloadProgress(monitorTarget, 0);
        throwIfAborted(signals);
        dispatchDownloadProgress(monitorTarget, 1);
        throwIfAborted(signals);
        return createDetectorInstance(expectedInputLanguages, signal);
      });
    }

    const pageDetector = definePageInterface("LanguageDetector", detectorPrototype);

    pageDetector.availability = exportFunction(function (options) {
      return pagePromise(function (resolve, reject) {
        try {
          readExpectedInputLanguages(options);
        } catch (error) {
          reject(toPageError(error));
          return;
        }
        requestAvailability().then(resolve, function (error) {
          reject(toPageError(error));
        });
      });
    }, pageWin);

    pageDetector.create = exportFunction(function (options) {
      return pagePromise(function (resolve, reject) {
        let creation;
        try {
          creation = createDetector(options);
        } catch (error) {
          reject(toPageError(error));
          return;
        }
        creation.then(resolve, function (error) {
          reject(toPageError(error));
        });
      });
    }, pageWin);
  }

  function installTranslator() {
    const translatorPrototype = new pageWin.Object();

    function createTranslatorInstance(languagePair, creationSignal) {
      const destroyController = createDestroyController(creationSignal);

      function translateText(input, options) {
        const signals = [destroyController.signal];
        const signal = readSignal(options);
        if (signal) signals.push(signal);
        if (destroyController.signal.aborted) {
          return Promise.reject(
            failure(pageDomException("Translator は破棄されています", "InvalidStateError")),
          );
        }
        const text = String(input);
        if (text.length > INPUT_QUOTA) {
          return Promise.reject(
            failure(pageDomException("入力が長すぎます", "QuotaExceededError")),
          );
        }
        return abortable(function () {
          return sendToEngine({
            action: "translate",
            sourceLanguage: languagePair.sourceLanguage,
            targetLanguage: languagePair.targetLanguage,
            text: text,
          }).then(function (result) {
            return String(result.text);
          });
        }, signals);
      }

      function startTranslation(input, options) {
        try {
          return translateText(input, options);
        } catch (error) {
          return Promise.reject(error);
        }
      }

      const instance = pageWin.Object.create(translatorPrototype);
      defineReadOnly(instance, "sourceLanguage", languagePair.sourceLanguage);
      defineReadOnly(instance, "targetLanguage", languagePair.targetLanguage);
      defineReadOnly(instance, "inputQuota", INPUT_QUOTA);

      instance.translate = exportFunction(function (input, options) {
        return pagePromise(function (resolve, reject) {
          startTranslation(input, options).then(resolve, function (error) {
            reject(toPageError(error));
          });
        });
      }, pageWin);

      // オンデバイス翻訳は部分結果を返さないため、翻訳完了時に 1 チャンクで流す
      instance.translateStreaming = exportFunction(function (input, options) {
        return new pageWin.ReadableStream(
          cloneInto(
            {
              start: function (controller) {
                const waivedController = waive(controller);
                startTranslation(input, options).then(
                  function (translated) {
                    waivedController.enqueue(translated);
                    waivedController.close();
                  },
                  function (error) {
                    waivedController.error(toPageError(error));
                  },
                );
              },
            },
            pageWin,
            { cloneFunctions: true },
          ),
        );
      }, pageWin);

      instance.measureInputUsage = exportFunction(function (input, options) {
        return pagePromise(function (resolve, reject) {
          try {
            const signal = readSignal(options);
            if (destroyController.signal.aborted) {
              reject(pageDomException("Translator は破棄されています", "InvalidStateError"));
            } else if (signal && signal.aborted) {
              reject(abortReason(signal));
            } else {
              resolve(String(input).length);
            }
          } catch (error) {
            reject(toPageError(error));
          }
        });
      }, pageWin);

      instance.destroy = exportFunction(function () {
        destroyController.abort(pageDomException("Translator は破棄されました", "AbortError"));
      }, pageWin);

      return instance;
    }

    function createTranslator(options) {
      const languagePair = readLanguagePair(options);
      const signal = readSignal(options);
      const monitor = waive(options).monitor;
      if (monitor !== undefined && monitor !== null && typeof monitor !== "function") {
        throw failure(new pageWin.TypeError("monitor must be a function"));
      }
      const signals = signal ? [signal] : [];
      const monitorTarget = new pageWin.EventTarget();
      if (monitor) monitor(monitorTarget);

      return abortable(function () {
        return sendToEngine({
          action: "availability",
          sourceLanguage: languagePair.sourceLanguage,
          targetLanguage: languagePair.targetLanguage,
        }).then(function (result) {
          // 可用性の応答待ちの間に中断されていたら、進捗イベントも配送しない
          throwIfAborted(signals);
          if (result.availability === "unavailable") {
            throw failure(
              pageDomException("この言語の組み合わせは翻訳できません", "NotSupportedError"),
            );
          }
          // ページがユーザー操作なしに翻訳モデルを端末へダウンロードさせられないようにする
          if (result.availability !== "available" && !hasUserActivation()) {
            throw failure(
              pageDomException(
                "翻訳モデルのダウンロードにはユーザー操作が必要です",
                "NotAllowedError",
              ),
            );
          }
          dispatchDownloadProgress(monitorTarget, 0);
          // 進捗イベントの中で中断されていたら、モデルの準備を始めない
          throwIfAborted(signals);
          return sendToEngine({
            action: "create",
            sourceLanguage: languagePair.sourceLanguage,
            targetLanguage: languagePair.targetLanguage,
          });
        });
      }, signals).then(function () {
        // 翻訳エンジンは途中経過を通知しないため、開始と完了だけを伝える
        dispatchDownloadProgress(monitorTarget, 1);
        throwIfAborted(signals);
        return createTranslatorInstance(languagePair, signal);
      });
    }

    const pageTranslator = definePageInterface("Translator", translatorPrototype);

    pageTranslator.availability = exportFunction(function (options) {
      return pagePromise(function (resolve, reject) {
        let languagePair;
        try {
          languagePair = readLanguagePair(options);
        } catch (error) {
          reject(toPageError(error));
          return;
        }
        sendToEngine({
          action: "availability",
          sourceLanguage: languagePair.sourceLanguage,
          targetLanguage: languagePair.targetLanguage,
        }).then(
          function (result) {
            resolve(String(result.availability));
          },
          function (error) {
            reject(toPageError(error));
          },
        );
      });
    }, pageWin);

    pageTranslator.create = exportFunction(function (options) {
      return pagePromise(function (resolve, reject) {
        let creation;
        try {
          creation = createTranslator(options);
        } catch (error) {
          reject(toPageError(error));
          return;
        }
        creation.then(resolve, function (error) {
          reject(toPageError(error));
        });
      });
    }, pageWin);
  }

  if (!("LanguageDetector" in pageWin)) installLanguageDetector();
  if (!("Translator" in pageWin)) installTranslator();
})();
