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
        const val SAMPLE_SCRIPT_EN = """Hi, I'm Hana Kim, and I was the front-end developer on Team MealMate in Perplz cohort 3. Here is the project we built in 8 weeks, in about one minute.

The problem was simple. Students who don't want to eat alone have no easy way to find someone heading to the same cafeteria at the same time. In our survey, 78 percent of 212 students said they eat alone three or more times a week.

So we built an app that matches meal buddies by timetable and location. I built the matching screen and chat in React Native, and worked in weekly sprints with two designers and one back-end engineer.

The results beat our expectations. 540 people signed up during a 3-week beta, and 61 percent of requests ended in a match. The biggest lesson: a single sentence on the first screen doubled our conversion rate, more than any feature did.

In my next project, I want to bring that habit of deciding with data. Thanks for watching."""

        const val SAMPLE_SCRIPT_KO = """안녕하세요, 저는 퍼플즈 3기 '밥친구' 프로젝트에서 프론트엔드 개발을 맡았던 김하늘입니다. 오늘은 저희 팀이 8주 동안 만든 서비스를 1분 안에 소개해 드릴게요.

저희가 풀고 싶었던 문제는 간단했어요. 혼자 밥 먹기 싫은 대학생이 같은 시간에 같은 식당에 갈 사람을 찾기가 너무 어렵다는 거였죠. 설문에 답한 212명 중 78퍼센트가 일주일에 세 번 이상 혼자 먹는다고 했습니다.

그래서 저희는 시간표와 위치를 기준으로 식사 메이트를 매칭하는 앱을 만들었어요. 저는 React Native로 매칭 화면과 채팅을 구현했고, 디자이너 두 분, 백엔드 한 분과 함께 매주 스프린트를 돌렸습니다.

결과는 기대 이상이었어요. 베타 3주 동안 540명이 가입했고, 매칭 성공률은 61퍼센트였습니다. 가장 큰 배움은 기능보다 첫 화면의 문장 하나가 전환율을 두 배로 바꿨다는 사실이었어요.

다음 프로젝트에서는 이 경험을 살려 데이터로 결정하는 팀을 만들고 싶습니다. 들어 주셔서 감사합니다."""

        fun sampleScript(languageTag: String): String =
            if (languageTag.startsWith("ko")) SAMPLE_SCRIPT_KO else SAMPLE_SCRIPT_EN
    }
}
