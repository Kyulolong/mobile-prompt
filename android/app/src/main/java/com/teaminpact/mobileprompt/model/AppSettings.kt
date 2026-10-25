package com.teaminpact.mobileprompt.model

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.os.LocaleListCompat
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * User-tunable prompter settings, persisted in SharedPreferences and exposed
 * as Compose snapshot state so every screen re-renders on change.
 * Mirrors the iOS AppSettings key-for-key.
 */
class AppSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var fontSize: Double by pref("fontSize", 30.0)
    var readingLineFrac: Double by pref("readingLineFrac", 0.38)
    var scrimOpacity: Double by pref("scrimOpacity", 0.35)
    var mirrored: Boolean by pref("mirrored", false)
    var autoSpeed: Double by pref("autoSpeed", 45.0)
    var voiceMode: Boolean by pref("voiceMode", true)
    /** "yellow" | "green" | "cyan" | "pink" | "none" */
    var highlightStyle: String by pref("highlightStyle", "green")
    /** UI language: "system" | "ko" | "en" — applies immediately. */
    var appLanguage: String by pref("appLanguage", "system")
        private set
    // Portrait-mode script box: width fraction and top/bottom margins.
    var portraitWidthFrac: Double by pref("portraitWidthFrac", 1.0)
    var portraitTopFrac: Double by pref("portraitTopFrac", 0.0)
    var portraitBottomFrac: Double by pref("portraitBottomFrac", 0.0)

    /** Change the app language; AppCompat recreates activities with the new locale. */
    fun chooseAppLanguage(lang: String) {
        appLanguage = lang
        val locales = if (lang == "system") LocaleListCompat.getEmptyLocaleList()
                      else LocaleListCompat.forLanguageTags(lang)
        AppCompatDelegate.setApplicationLocales(locales)
    }

    // --- persistence -------------------------------------------------------

    private inline fun <reified T> pref(key: String, default: T): ReadWriteProperty<Any?, T> =
        object : ReadWriteProperty<Any?, T> {
            private var state by mutableStateOf(read(key, default))
            override fun getValue(thisRef: Any?, property: KProperty<*>): T = state
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
                state = value
                write(key, value)
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun <T> read(key: String, default: T): T = when (default) {
        is Double -> (if (prefs.contains(key)) prefs.getFloat(key, 0f).toDouble() else default) as T
        is Boolean -> prefs.getBoolean(key, default) as T
        is String -> (prefs.getString(key, default) ?: default) as T
        else -> default
    }

    private fun <T> write(key: String, value: T) {
        prefs.edit().apply {
            when (value) {
                is Double -> putFloat(key, value.toFloat())
                is Boolean -> putBoolean(key, value)
                is String -> putString(key, value)
            }
        }.apply()
    }

    companion object {
        const val SAMPLE_SCRIPT_EN = """Hello everyone, today I want to show you a teleprompter I built myself.

Most prompter apps out there scroll the text at a fixed speed. But the moment you speak a little faster or slower, the screen drifts away from you.

So I changed the approach. Instead of pushing the text at a constant pace, this app listens to my voice and follows exactly where I am reading, in real time.

If I pause for a moment, the screen simply waits. If I ad-lib a little, it doesn't lose my place. When I come back to the script, it picks up right where I am.

Numbers are no problem either. Things like 2025 or 30% are matched just fine, however you say them.

Best of all, it's completely free. It runs on your phone without internet, no payments, no API keys.

If the screen has been scrolling smoothly at your pace while you read this — it works. That's it for today. Don't forget to like and subscribe Kyulolong!"""

        const val SAMPLE_SCRIPT_KO = """안녕하세요 여러분, 오늘은 제가 직접 만든 프롬프터를 소개해 드릴게요.

시중에 나온 프롬프터 앱들은 대부분 정해진 속도로 글자가 흘러가죠. 그런데 말이 조금만 빨라지거나 느려지면 금방 어긋나 버려요. 특히 한국어는 인식이 잘 안 되는 경우가 많았고요.

그래서 생각을 바꿨습니다. 글자를 일정한 속도로 미는 게 아니라, 제 목소리를 듣고 지금 어디를 읽고 있는지 실시간으로 따라오게 만든 거예요.

잠깐 멈춰도 화면은 그대로 기다려 주고요. 중간에 애드립을 살짝 넣어도 위치를 잃지 않아요. 다시 대본으로 돌아오면 자연스럽게 이어서 따라옵니다.

숫자도 문제없어요. 예를 들어 2025년, 30퍼센트, 이런 표현도 그대로 맞춰서 넘어갑니다.

무엇보다 전부 무료예요. 인터넷 없이 폰에서도 돌아가고, 별도의 결제나 API 키도 필요 없습니다.

여기까지 읽는 동안 화면이 제 속도에 맞춰 부드럽게 내려왔다면, 성공입니다. 오늘 영상은 여기까지고요, 다음에 더 유용한 걸로 찾아올게요. 규로롱 구독과 좋아요 잊지 마세요!"""

        fun sampleScript(languageTag: String): String =
            if (languageTag.startsWith("ko")) SAMPLE_SCRIPT_KO else SAMPLE_SCRIPT_EN
    }
}
