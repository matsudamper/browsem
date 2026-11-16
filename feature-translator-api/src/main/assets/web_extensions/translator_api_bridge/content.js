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
    if (error && error.pageError) return error.pageError;
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
  function requestTranslationEngine(message) {
    return browser.runtime.sendNativeMessage(NATIVE_APP, message).then(function (response) {
      if (response && response.useGeckoEngine) {
        return browser.runtime.sendMessage(message);
      }
      return response;
    });
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

  // signal が中断されたら reject する Promise と競わせる。翻訳エンジン側の処理は止めず、結果を捨てる
  function abortable(promise, signals) {
    return new Promise(function (resolve, reject) {
      for (let i = 0; i < signals.length; i++) {
        const signal = signals[i];
        if (signal.aborted) {
          reject(failure(signal.reason));
          return;
        }
        signal.addEventListener(
          "abort",
          function () {
            reject(failure(signal.reason));
          },
          { once: true },
        );
      }
      promise.then(resolve, reject);
    });
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

    function createDetectorInstance(expectedInputLanguages) {
      const destroyController = new AbortController();

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
        return abortable(
          sendToEngine({ action: "detectLanguage", text: text }).then(function (result) {
            return toDetectionResults(result.languages || []);
          }),
          signals,
        );
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
              reject(signal.reason);
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

      return abortable(requestAvailability(), signal ? [signal] : []).then(function (availability) {
        if (availability === "unavailable") {
          throw failure(pageDomException("言語を判定できません", "NotSupportedError"));
        }
        // 言語判定のモデルは同梱されておりダウンロードが起きないため、開始と完了を続けて伝える
        dispatchDownloadProgress(monitorTarget, 0);
        dispatchDownloadProgress(monitorTarget, 1);
        return createDetectorInstance(expectedInputLanguages);
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

    function createTranslatorInstance(languagePair) {
      const destroyController = new AbortController();

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
        return abortable(
          sendToEngine({
            action: "translate",
            sourceLanguage: languagePair.sourceLanguage,
            targetLanguage: languagePair.targetLanguage,
            text: text,
          }).then(function (result) {
            return String(result.text);
          }),
          signals,
        );
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
              reject(signal.reason);
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

      return abortable(
        sendToEngine({
          action: "availability",
          sourceLanguage: languagePair.sourceLanguage,
          targetLanguage: languagePair.targetLanguage,
        }).then(function (result) {
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
          return sendToEngine({
            action: "create",
            sourceLanguage: languagePair.sourceLanguage,
            targetLanguage: languagePair.targetLanguage,
          });
        }),
        signals,
      ).then(function () {
        // 翻訳エンジンは途中経過を通知しないため、開始と完了だけを伝える
        dispatchDownloadProgress(monitorTarget, 1);
        return createTranslatorInstance(languagePair);
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
