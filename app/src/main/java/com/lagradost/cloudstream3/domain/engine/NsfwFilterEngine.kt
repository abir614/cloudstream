package com.lagradost.cloudstream3.domain.engine

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.databinding.DialogNsfwSensitivityThresholdsBinding
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.utils.ImageLoader
import java.util.Locale

/**
 * Configurable Content Safety Sensitivity Levels:
 * - EXTREME (default 0.15f): Super Extreme peak shield (strictly blocks all adult, ecchi, beach, summer, swim, bikini, exposure, empty posters, provocative tropes).
 * - MODERATE (default 0.30f): Family Level sensitivity (strictly blocks explicit adult, ecchi, lewd, smut; preserves clean anime and summer/beach titles).
 * - LOW (default 0.55f): Hardcore sensitivity (strictly blocks explicit adult, pornography, hentai, and hardcore content).
 *
 * Each sensitivity level has a configurable Visual Detection Threshold (0.01f – 1.00f).
 * Invariant constraint: Extreme < Moderate < Low.
 */
enum class NsfwFilterLevel(
    val key: String,
    val defaultVisualThreshold: Float
) {
    LOW("low", 0.55f),
    MODERATE("moderate", 0.30f),
    EXTREME("extreme", 0.15f);

    val visualThreshold: Float
        get() = NsfwFilterEngine.getVisualThreshold(null, this)

    companion object {
        fun fromString(value: String?): NsfwFilterLevel {
            return entries.firstOrNull { it.key.equals(value, ignoreCase = true) } ?: MODERATE
        }
    }
}

/**
 * Robust Multi-Vector NSFW Classification Engine.
 *
 * Integrates:
 * 1. On-device lightweight quantized neural network AI model ([OnDeviceNsfwClassifier]) for visual posters.
 * 2. Multi-tier pure Kotlin adult, ecchi, and extreme sensitivity keyword guardian.
 * 3. Dynamic visual threshold scales (0.01 – 1.00) with strict constraint ordering.
 */
object NsfwFilterEngine {

    const val DEFAULT_THRESHOLD_EXTREME = 0.15f
    const val DEFAULT_THRESHOLD_MODERATE = 0.30f
    const val DEFAULT_THRESHOLD_LOW = 0.55f

    const val PREF_KEY_THRESHOLD_EXTREME = "nsfw_visual_threshold_extreme_key"
    const val PREF_KEY_THRESHOLD_MODERATE = "nsfw_visual_threshold_moderate_key"
    const val PREF_KEY_THRESHOLD_LOW = "nsfw_visual_threshold_low_key"

    private val _configVersion = kotlinx.coroutines.flow.MutableStateFlow(0)
    val configVersion: kotlinx.coroutines.flow.StateFlow<Int> = _configVersion

    fun onFilterConfigChanged() {
        // Retain raw AI scores in LRU cache so sensitivity adjustments take effect immediately
        // during rescan without re-running heavy neural network inference.
        CloudStreamApp.context?.let { ImageLoader.reconfigure(it) } ?: ImageLoader.resetImageClient()
        _configVersion.value += 1
    }

    /**
     * Resolves the configured visual threshold for a sensitivity level.
     * Guaranteed bounds: 0.01f <= threshold <= 1.00f.
     */
    fun getVisualThreshold(context: Context? = null, level: NsfwFilterLevel = getFilterLevel(context)): Float {
        val ctx = context ?: CloudStreamApp.context ?: return level.defaultVisualThreshold
        return try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
            val key = when (level) {
                NsfwFilterLevel.EXTREME -> ctx.getString(R.string.nsfw_visual_threshold_extreme_key)
                NsfwFilterLevel.MODERATE -> ctx.getString(R.string.nsfw_visual_threshold_moderate_key)
                NsfwFilterLevel.LOW -> ctx.getString(R.string.nsfw_visual_threshold_low_key)
            }
            val raw = prefs.getFloat(key, level.defaultVisualThreshold)
            raw.coerceIn(0.01f, 1.00f)
        } catch (_: Throwable) {
            level.defaultVisualThreshold
        }
    }

    /**
     * Returns the current (Extreme, Moderate, Low) visual thresholds as a Triple.
     */
    fun getAllVisualThresholds(context: Context? = null): Triple<Float, Float, Float> {
        val extreme = getVisualThreshold(context, NsfwFilterLevel.EXTREME)
        val moderate = getVisualThreshold(context, NsfwFilterLevel.MODERATE)
        val low = getVisualThreshold(context, NsfwFilterLevel.LOW)
        return Triple(extreme, moderate, low)
    }

    /**
     * Persists new visual thresholds, strictly enforcing Extreme < Moderate < Low in [0.01, 1.00].
     */
    fun setVisualThresholds(
        context: Context,
        extremeIn: Float,
        moderateIn: Float,
        lowIn: Float,
        modifiedTier: NsfwFilterLevel? = null
    ): Triple<Float, Float, Float> {
        val (e, m, l) = enforceThresholdConstraints(extremeIn, moderateIn, lowIn, modifiedTier)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit {
            putFloat(context.getString(R.string.nsfw_visual_threshold_extreme_key), e)
            putFloat(context.getString(R.string.nsfw_visual_threshold_moderate_key), m)
            putFloat(context.getString(R.string.nsfw_visual_threshold_low_key), l)
        }
        onFilterConfigChanged()
        return Triple(e, m, l)
    }

    /**
     * Resets visual thresholds to defaults (0.15, 0.30, 0.55).
     */
    fun resetVisualThresholdsToDefault(context: Context): Triple<Float, Float, Float> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit {
            putFloat(context.getString(R.string.nsfw_visual_threshold_extreme_key), DEFAULT_THRESHOLD_EXTREME)
            putFloat(context.getString(R.string.nsfw_visual_threshold_moderate_key), DEFAULT_THRESHOLD_MODERATE)
            putFloat(context.getString(R.string.nsfw_visual_threshold_low_key), DEFAULT_THRESHOLD_LOW)
        }
        onFilterConfigChanged()
        return Triple(DEFAULT_THRESHOLD_EXTREME, DEFAULT_THRESHOLD_MODERATE, DEFAULT_THRESHOLD_LOW)
    }

    /**
     * Mathematically enforces:
     * 0.01 <= Extreme < Moderate < Low <= 1.00
     * Dynamically shifts neighboring thresholds when one is adjusted.
     */
    fun enforceThresholdConstraints(
        extremeIn: Float,
        moderateIn: Float,
        lowIn: Float,
        modifiedTier: NsfwFilterLevel? = null
    ): Triple<Float, Float, Float> {
        var e = (Math.round(extremeIn * 100f) / 100f).coerceIn(0.01f, 0.98f)
        var m = (Math.round(moderateIn * 100f) / 100f).coerceIn(0.02f, 0.99f)
        var l = (Math.round(lowIn * 100f) / 100f).coerceIn(0.03f, 1.00f)

        when (modifiedTier) {
            NsfwFilterLevel.EXTREME -> {
                if (m <= e) m = (e + 0.01f).coerceAtMost(0.99f)
                if (l <= m) l = (m + 0.01f).coerceAtMost(1.00f)
            }
            NsfwFilterLevel.LOW -> {
                if (m >= l) m = (l - 0.01f).coerceAtLeast(0.02f)
                if (e >= m) e = (m - 0.01f).coerceAtLeast(0.01f)
            }
            NsfwFilterLevel.MODERATE, null -> {
                if (e >= m) e = (m - 0.01f).coerceAtLeast(0.01f)
                if (l <= m) l = (m + 0.01f).coerceAtMost(1.00f)
            }
        }

        if (e >= m) e = (m - 0.01f).coerceAtLeast(0.01f)
        if (l <= m) l = (m + 0.01f).coerceAtMost(1.00f)

        return Triple(
            Math.round(e * 100f) / 100f,
            Math.round(m * 100f) / 100f,
            Math.round(l * 100f) / 100f
        )
    }

    /**
     * Displays an interactive dialog with live scales for Extreme, Moderate, and Low sensitivities.
     */
    fun showSensitivityThresholdsDialog(activity: Activity, onDismissCallback: () -> Unit = {}) {
        if (activity.isFinishing || (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed)) return
        val context = activity
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(context)
        val currentLevelStr = settingsManager.getString(context.getString(R.string.nsfw_filter_level_key), "moderate") ?: "moderate"
        var selectedLevel = NsfwFilterLevel.fromString(currentLevelStr)

        val (initE, initM, initL) = getAllVisualThresholds(context)
        var curE = initE
        var curM = initM
        var curL = initL

        val binding = DialogNsfwSensitivityThresholdsBinding.inflate(
            LayoutInflater.from(context), null, false
        )

        val builder = AlertDialog.Builder(context, R.style.AlertDialogCustom).setView(binding.root)
        val dialog = builder.create()

        fun updateUI() {
            binding.radioExtreme.isChecked = (selectedLevel == NsfwFilterLevel.EXTREME)
            binding.radioModerate.isChecked = (selectedLevel == NsfwFilterLevel.MODERATE)
            binding.radioLow.isChecked = (selectedLevel == NsfwFilterLevel.LOW)

            binding.valueExtreme.text = String.format(Locale.ROOT, "%.2f", curE)
            binding.valueModerate.text = String.format(Locale.ROOT, "%.2f", curM)
            binding.valueLow.text = String.format(Locale.ROOT, "%.2f", curL)

            val pE = Math.round(curE * 100f).coerceIn(1, 100)
            val pM = Math.round(curM * 100f).coerceIn(1, 100)
            val pL = Math.round(curL * 100f).coerceIn(1, 100)

            if (binding.seekbarExtreme.progress != pE) binding.seekbarExtreme.progress = pE
            if (binding.seekbarModerate.progress != pM) binding.seekbarModerate.progress = pM
            if (binding.seekbarLow.progress != pL) binding.seekbarLow.progress = pL
        }

        binding.containerExtreme.setOnClickListener {
            selectedLevel = NsfwFilterLevel.EXTREME
            updateUI()
        }
        binding.containerModerate.setOnClickListener {
            selectedLevel = NsfwFilterLevel.MODERATE
            updateUI()
        }
        binding.containerLow.setOnClickListener {
            selectedLevel = NsfwFilterLevel.LOW
            updateUI()
        }

        var isUpdating = false
        fun handleSeekChange(tier: NsfwFilterLevel) {
            if (isUpdating) return
            isUpdating = true
            try {
                val rawE = binding.seekbarExtreme.progress.coerceAtLeast(1) / 100f
                val rawM = binding.seekbarModerate.progress.coerceAtLeast(1) / 100f
                val rawL = binding.seekbarLow.progress.coerceAtLeast(1) / 100f

                val (adjE, adjM, adjL) = enforceThresholdConstraints(rawE, rawM, rawL, tier)
                curE = adjE
                curM = adjM
                curL = adjL
                updateUI()
            } finally {
                isUpdating = false
            }
        }

        binding.seekbarExtreme.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) handleSeekChange(NsfwFilterLevel.EXTREME)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        binding.seekbarModerate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) handleSeekChange(NsfwFilterLevel.MODERATE)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        binding.seekbarLow.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) handleSeekChange(NsfwFilterLevel.LOW)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        binding.btnReset.setOnClickListener {
            curE = DEFAULT_THRESHOLD_EXTREME
            curM = DEFAULT_THRESHOLD_MODERATE
            curL = DEFAULT_THRESHOLD_LOW
            updateUI()
        }

        binding.btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        binding.btnApply.setOnClickListener {
            settingsManager.edit {
                putString(context.getString(R.string.nsfw_filter_level_key), selectedLevel.key)
            }
            setVisualThresholds(context, curE, curM, curL)
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            onDismissCallback()
        }

        updateUI()
        try {
            if (!activity.isFinishing && !(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed)) {
                dialog.show()
            }
        } catch (_: Exception) {
            // Guard against window manager race conditions on older/backgrounded devices
        }
    }

    private val HARDCORE_KEYWORDS = hashSetOf(
        "xxx", "porn", "porno", "pornography", "nsfw", "jav", "r18", "r-18", "r18+", "+18", "18+", "18plus",
        "over18", "18only", "adult", "erotica", "erotic", "hentai", "hanime", "chikan", "paizuri", "nakadashi",
        "shibari", "bukkake", "creampie", "blowjob", "gangbang", "deepthroat", "swinger", "cuckold", "masturbation",
        "incest", "uncensored", "decensored", "nude", "nudity", "naked", "topless", "fella", "cunnilingus",
        "pegging", "stepmom", "stepmother", "stepsister", "stepdaughter", "stepson", "stepbrother", "taboo",
        "voyeur", "upskirt", "onlyfans", "fansly", "striptease", "stripper", "camgirl", "playboy", "playmate",
        "penthouse", "orgy", "rance", "kuroinu", "taimanin", "mankitsu", "overflow", "euphoria", "futabu",
        "shoujoramune", "otomedori", "resortboin", "onichichi", "seikatsushuukan", "bunnywalker", "pinkpineapple",
        "queenbee", "poros", "greenbunny", "whitebear", "naturalhigh", "nhentai", "tsumino", "fakku", "pornhub",
        "xvideos", "xnxx", "spankbang", "eporner", "missav", "supjav", "javhd", "rule34", "hitomi"
    )

    private val SUGGESTIVE_KEYWORDS = hashSetOf(
        "ecchi", "ero-anime", "eroanime", "eroge", "ero", "ahegao", "doujin", "doujinshi", "futanari",
        "netorare", "ntr", "smut", "lewd", "lust", "sensual", "sex", "sexy", "yuri", "yaoi",
        "softcore", "hardcore", "gravure", "lingerie", "cleavage", "oppai", "boob", "boobs", "breast",
        "breasts", "busty", "milf", "thong", "panties", "panty", "fetish", "bdsm", "bondage", "sadism",
        "masochism", "dxd", "shinmai", "valkyrie", "interspecies", "chijo", "seduce", "seduction",
        "submissive", "dominant", "virgin", "eromanga"
    )

    private val EXTREME_KEYWORDS = hashSetOf(
        "beach", "summer", "bikini", "swimsuit", "swimwear", "beachwear", "monokini", "microkini", "mizugi",
        "pool", "swimming", "halfbody", "half-body", "exposed", "topless", "bare", "bareback", "barechested",
        "underboob", "sideboob", "nudity", "nude", "naked", "lingerie", "underwear", "bra", "thong", "cleavage",
        "bathing", "bath", "hotspring", "hot-spring", "onsen", "harem", "seductive", "provocative",
        "fanservice", "fan-service", "waifu", "suggestive", "pantyshot", "panty-shot", "spank", "spanking",
        "bathhouse"
    )

    private val SUPER_EXTREME_KEYWORDS = hashSetOf(
        "succubus", "incubus", "peeping", "voyeur", "voyeurism", "exhibitionism", "upskirt", "downblouse",
        "nudist", "nudism", "naturist", "naturism", "maid", "bunnygirl", "crossdress", "crossdressing",
        "femboy", "otokonoko", "concubine", "courtesan", "brothel", "prostitute", "prostitution", "escort",
        "callgirl", "redlight", "soapland", "aphrodisiac", "seductress", "temptress", "mistress", "nightwear",
        "sleepwear", "babydoll", "negligee", "corset", "fishnet", "stockings", "garter", "nukige",
        "galge", "tentacle", "tentacles", "pantsu", "monmusu", "tsugumomo", "mixedbath", "mixed-bath"
    )

    private val HARDCORE_STEMS = arrayOf(
        "hentai", "hanime", "porn", "porno", "xxx", "jav", "r18", "+18", "18+", "eroti", "bukkake",
        "nakadashi", "paizuri", "chikan", "shibari", "creampie", "blowjob", "deepthroat", "swinger",
        "cuckold", "cunnilingus", "pegging", "gangbang", "incest", "stepmom", "stepmother",
        "stepsister", "stepdaughter", "stepson", "stepbrother", "nude", "nudity", "topless",
        "uncensor", "decensor", "rule34", "onlyfans", "fansly", "striptease", "stripper", "camgirl",
        "playboy", "playmate", "nhentai", "tsumino", "spankbang", "eporner", "missav", "supjav"
    )

    private val SUGGESTIVE_STEMS = arrayOf(
        "ecchi", "ero-anime", "eroanime", "eroge", "ahegao", "doujin", "futanari", "netorare",
        "smut", "lewd", "oppai", "boob", "busty", "lingerie", "milf", "thong", "panty", "panties",
        "fetish", "bdsm", "bondage", "sensual", "chijo", "eromanga", "dxd"
    )

    private val EXTREME_STEMS = arrayOf(
        "bikini", "monokini", "microkini", "mizugi", "halfbody",
        "half-body", "half body", "exposed", "topless", "bareback", "barechested",
        "underboob", "sideboob", "cleavage", "onsen", "bathhouse", "hotspring",
        "pantyshot", "panty-shot", "spank", "seductive", "provocative"
    )

    private val SUPER_EXTREME_STEMS = arrayOf(
        "succubus", "incubus", "peeping", "voyeur", "upskirt", "downblouse", "nudis", "naturis",
        "bunnygirl", "bunny-girl", "bunny girl", "crossdress", "femboy", "otokonoko", "concubine",
        "courtesan", "brothel", "prostitut", "callgirl", "redlight", "soapland", "aphrodisiac",
        "seductress", "temptress", "negligee", "babydoll", "fishnet", "nukige", "galge", "tentacle",
        "pantsu", "monmusu", "tsugumomo", "step-sister", "step-brother", "step-mother", "step-father",
        "step-daughter", "step-son", "hxeros", "immoral-guild", "futoku"
    )

    private val HARDCORE_PHRASES = arrayOf(
        "boku no pico", "bible black", "oni chichi", "night shift nurses", "la blue girl",
        "seikatsu shuukan", "resort boin", "otome dori", "shoujo ramune"
    )

    private val SUGGESTIVE_PHRASES = arrayOf(
        "high school dxd", "shinmai maou", "yosuga no sora", "aki sora", "redo of healer",
        "interspecies reviewers", "valkyrie drive", "prison school", "kiss x sis", "to love-ru",
        "to love ru", "seikon no qwaser"
    )

    private val EXTREME_PHRASES = arrayOf(
        "hot spring", "fan service", "panty shot", "mixed bath"
    )

    private val SUPER_EXTREME_PHRASES = arrayOf(
        "domestic girlfriend", "domestic na kanojo", "kuzu no honkai", "scum's wish",
        "masou gakuen", "hybrid x heart", "manyuu hikenchou", "ladies versus butlers",
        "shimoneta", "to love ru darkness", "motto to love ru", "peter grill",
        "monster musume", "ishuzoku reviewers", "ayane de battle", "kissxsis",
        "erotic romance", "sensual romance", "massage parlor", "adult video",
        "mature content", "adult anime", "18+ anime", "eromanga sensei",
        "world's end harem", "shuumatsu no harem", "immoral guild", "futoku no guild",
        "dokyuu hentai hxeros", "okusan ga seitokaichou", "my wife is the student council president",
        "miru tights", "peter grill and the philosopher's time", "why the hell are you here teacher",
        "nande koko ni sensei ga", "super hxeros", "valkyrie drive mermaid", "masou gakuen hxh"
    )

    private val CJK_HARDCORE = arrayOf(
        "成年", "18禁", "無修正", "裏ビデオ", "潮吹き", "乱交", "ポルノ", "パイズリ", "フェラ", "中出し"
    )

    private val CJK_SUGGESTIVE = arrayOf(
        "エロ", "エッチ", "変態", "巨乳", "爆乳", "痴女", "調教",
        "下着", "露出", "同人", "熟女", "性的", "美乳", "貧乳", "微乳", "パンチラ", "性愛", "アダルト"
    )

    private val CJK_EXTREME = arrayOf(
        "水着", "ビキニ", "グラビア", "混浴", "温泉", "お風呂", "ハーレム", "お色気", "水着回", "盗撮"
    )

    private val CJK_SUPER_EXTREME = arrayOf(
        "裏垢", "パパ活", "援助交際", "援交", "買春", "売春", "風俗", "ソープ", "ピンサロ", "セクキャバ",
        "デリヘル", "出会い系", "官能", "夜這い", "近親相姦", "義母", "義妹", "義姉", "義父", "催眠",
        "触手", "露出狂", "女体盛り", "媚薬", "肉感", "巨尻", "美尻", "谷間", "パンスト", "下着姿",
        "半裸", "全裸", "裸体", "肢体", "艶", "秘部", "水着姿", "エロアニメ", "エロ動画"
    )

    private val CAMEL_CASE_REGEX = Regex("([a-z])([A-Z])")
    private val DELIM_REGEX = Regex("[^a-z0-9+]+")

    /**
     * Resolves the user's active filter sensitivity level.
     */
    fun getFilterLevel(ctx: Context? = null): NsfwFilterLevel {
        val context = ctx ?: CloudStreamApp.context ?: return NsfwFilterLevel.MODERATE
        return try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val levelStr = prefs.getString(context.getString(R.string.nsfw_filter_level_key), "moderate")
            NsfwFilterLevel.fromString(levelStr)
        } catch (_: Throwable) {
            NsfwFilterLevel.MODERATE
        }
    }

    /**
     * Classifies title, URL, or tags against high-speed pure Kotlin classifier.
     * Evaluates in microseconds without crossing JNI or creating heap garbage.
     */
    fun isNsfwText(
        title: String,
        url: String,
        tags: String? = null,
        level: NsfwFilterLevel = getFilterLevel()
    ): Boolean {
        return isTextNsfwSingle(title, level) ||
               isTextNsfwSingle(url, level) ||
               (tags != null && isTextNsfwSingle(tags, level))
    }

    private fun isTextNsfwSingle(text: String, level: NsfwFilterLevel): Boolean {
        if (text.isEmpty()) return false

        // 1. CJK stem checks
        // Hardcore CJK active in ALL levels (LOW, MODERATE, EXTREME)
        for (stem in CJK_HARDCORE) {
            if (text.contains(stem)) return true
        }
        // Suggestive CJK active in MODERATE & EXTREME
        if (level >= NsfwFilterLevel.MODERATE) {
            for (stem in CJK_SUGGESTIVE) {
                if (text.contains(stem)) return true
            }
        }
        // Extreme & Super Extreme CJK active ONLY in EXTREME
        if (level >= NsfwFilterLevel.EXTREME) {
            for (stem in CJK_EXTREME) {
                if (text.contains(stem)) return true
            }
            for (stem in CJK_SUPER_EXTREME) {
                if (text.contains(stem)) return true
            }
        }

        val lower = text.lowercase()
        val spaced = text.replace(CAMEL_CASE_REGEX, "$1 $2").lowercase()
        val normalized = lower.replace(DELIM_REGEX, " ")
        val spacedNormalized = spaced.replace(DELIM_REGEX, " ")

        // 2. Multi-word phrases
        // Hardcore phrases active in ALL levels
        for (phrase in HARDCORE_PHRASES) {
            if (lower.contains(phrase) || normalized.contains(phrase) || spaced.contains(phrase) || spacedNormalized.contains(phrase)) return true
        }
        // Suggestive phrases active in MODERATE & EXTREME
        if (level >= NsfwFilterLevel.MODERATE) {
            for (phrase in SUGGESTIVE_PHRASES) {
                if (lower.contains(phrase) || normalized.contains(phrase) || spaced.contains(phrase) || spacedNormalized.contains(phrase)) return true
            }
        }
        // Extreme & Super Extreme phrases active ONLY in EXTREME
        if (level >= NsfwFilterLevel.EXTREME) {
            for (phrase in EXTREME_PHRASES) {
                if (lower.contains(phrase) || normalized.contains(phrase) || spaced.contains(phrase) || spacedNormalized.contains(phrase)) return true
            }
            for (phrase in SUPER_EXTREME_PHRASES) {
                if (lower.contains(phrase) || normalized.contains(phrase) || spaced.contains(phrase) || spacedNormalized.contains(phrase)) return true
            }
        }

        // 3. Substring stem checks (catches compound words without spaces)
        // Hardcore stems active in ALL levels
        for (stem in HARDCORE_STEMS) {
            if (lower.contains(stem) || spaced.contains(stem)) return true
        }
        // Suggestive stems active in MODERATE & EXTREME
        if (level >= NsfwFilterLevel.MODERATE) {
            for (stem in SUGGESTIVE_STEMS) {
                if (lower.contains(stem) || spaced.contains(stem)) return true
            }
        }
        // Extreme & Super Extreme stems active ONLY in EXTREME
        if (level >= NsfwFilterLevel.EXTREME) {
            for (stem in EXTREME_STEMS) {
                if (lower.contains(stem) || spaced.contains(stem)) return true
            }
            for (stem in SUPER_EXTREME_STEMS) {
                if (lower.contains(stem) || spaced.contains(stem)) return true
            }
        }

        // 4. Delimited token matching (zero array allocation iteration)
        val combined = "$normalized $spacedNormalized"
        val len = combined.length
        var start = 0
        while (start < len) {
            while (start < len && combined[start] == ' ') start++
            if (start >= len) break
            var end = start
            while (end < len && combined[end] != ' ') end++
            val token = combined.substring(start, end)
            if (HARDCORE_KEYWORDS.contains(token) ||
                (level >= NsfwFilterLevel.MODERATE && SUGGESTIVE_KEYWORDS.contains(token)) ||
                (level >= NsfwFilterLevel.EXTREME && (EXTREME_KEYWORDS.contains(token) || SUPER_EXTREME_KEYWORDS.contains(token)))
            ) {
                return true
            }
            start = end + 1
        }
        return false
    }

    /**
     * URL classification helper.
     */
    fun isNsfwUrl(url: String, level: NsfwFilterLevel = getFilterLevel()): Boolean {
        return isNsfwText("", url, null, level)
    }

    /**
     * Checks if a SearchResponse item is classified as adult/NSFW.
     * Evaluates item type, provider declaration, cached AI poster classification, and metadata.
     */
    fun isNsfw(item: SearchResponse, level: NsfwFilterLevel = getFilterLevel()): Boolean {
        if (item.type == TvType.NSFW) return true

        // In EXTREME filter level, contents with empty/missing/blank/non-http posters are strictly filtered out
        if (level == NsfwFilterLevel.EXTREME) {
            val poster = item.posterUrl
            if (poster.isNullOrBlank() || poster == "null" || (!poster.startsWith("http://") && !poster.startsWith("https://"))) {
                return true
            }
        }

        // Check if provider itself has an adult name or is declared as an adult/NSFW provider
        if (isTextNsfwSingle(item.apiName, level)) return true
        APIHolder.getApiFromNameNull(item.apiName)?.let { provider ->
            if (isNsfwProvider(provider)) return true
        }

        // Check AI model visual cache if poster URL has already been evaluated as NSFW
        item.posterUrl?.let { url ->
            if (OnDeviceNsfwClassifier.isCachedNsfw(url, null, level)) return true
        }

        // Multi-vector text check on title, item URL, and poster URL
        if (isNsfwText(item.name, item.url, item.posterUrl, level)) {
            return true
        }

        return false
    }

    /**
     * Checks if a LoadResponse item is classified as adult/NSFW.
     */
    fun isNsfw(item: LoadResponse, level: NsfwFilterLevel = getFilterLevel()): Boolean {
        if (item.type == TvType.NSFW) return true

        // In EXTREME filter level, contents with empty/missing/blank/non-http posters are strictly filtered out
        if (level == NsfwFilterLevel.EXTREME) {
            val poster = item.posterUrl ?: item.backgroundPosterUrl
            if (poster.isNullOrBlank() || poster == "null" || (!poster.startsWith("http://") && !poster.startsWith("https://"))) {
                return true
            }
        }

        // Check provider name and declaration
        if (isTextNsfwSingle(item.apiName, level)) return true
        APIHolder.getApiFromNameNull(item.apiName)?.let { provider ->
            if (isNsfwProvider(provider)) return true
        }

        // Check content rating
        item.contentRating?.let { rating ->
            val r = rating.trim().lowercase()
            if (r == "18+" || r == "r18" || r == "r-18" || r == "nc-17" || r == "x" || r == "xxx" || r == "adult") {
                return true
            }
            if (level >= NsfwFilterLevel.EXTREME && (r == "tv-ma" || r == "mature" || r.contains("erotic"))) {
                return true
            }
        }

        item.posterUrl?.let { url ->
            if (OnDeviceNsfwClassifier.isCachedNsfw(url, null, level)) return true
        }
        item.backgroundPosterUrl?.let { url ->
            if (OnDeviceNsfwClassifier.isCachedNsfw(url, null, level)) return true
        }

        val tagsStr = item.tags?.joinToString(" ")
        if (isNsfwText(item.name, item.url, tagsStr, level)) {
            return true
        }
        item.posterUrl?.let { if (isNsfwUrl(it, level)) return true }
        item.backgroundPosterUrl?.let { if (isNsfwUrl(it, level)) return true }

        // In MODERATE and EXTREME, inspect synopsis/plot
        if (level >= NsfwFilterLevel.MODERATE) {
            item.plot?.let { plotText ->
                if (isTextNsfwSingle(plotText, level)) return true
            }
        }

        return false
    }

    /**
     * Checks if a provider API is declared as an adult/NSFW provider.
     */
    fun isNsfwProvider(api: MainAPI): Boolean {
        return api.supportedTypes.contains(TvType.NSFW) || api.supportedTypes == setOf(TvType.NSFW)
    }

    fun isNsfwProvider(repo: APIRepository): Boolean {
        return isNsfwProvider(repo.api)
    }

    /**
     * Checks if a home feed row is an adult/NSFW row based on category name or row contents.
     */
    fun isNsfwFeedRow(name: String, items: List<SearchResponse>, level: NsfwFilterLevel = getFilterLevel()): Boolean {
        // 1. If the row category name itself indicates adult, ecchi, or summer/beach swimsuit, eliminate the row
        if (isNsfwText(name, "", null, level)) {
            return true
        }

        // 2. If row items predominantly contain adult content
        if (items.isNotEmpty()) {
            val nsfwCount = items.count { isNsfw(it, level) }
            if (nsfwCount > 0 && (nsfwCount * 2 >= items.size || (items.size >= 4 && nsfwCount * 3 >= items.size))) {
                return true
            }
        }
        return false
    }

    /**
     * Filters a list of SearchResponse items based on whether NSFW content is enabled.
     * When disabled (default), all adult items are completely removed.
     */
    fun filterSearchResults(data: List<SearchResponse>, isNsfwEnabled: Boolean): List<SearchResponse> {
        if (isNsfwEnabled) return data
        val level = getFilterLevel()
        return data.filterNot { isNsfw(it, level) }
    }

    /**
     * Filters a home page row, removing adult items or eliminating the row entirely if empty or adult.
     * When disabled (default), all adult categories and items are completely removed.
     */
    fun filterHomePageList(data: HomePageList, isNsfwEnabled: Boolean): HomePageList? {
        if (isNsfwEnabled) return data
        val level = getFilterLevel()
        if (isNsfwFeedRow(data.name, data.list, level)) return null

        val filteredItems = data.list.filterNot { isNsfw(it, level) }
        if (filteredItems.isEmpty()) return null

        return data.copy(list = filteredItems)
    }

    /**
     * Filters provider APIs, excluding adult providers when NSFW is disabled.
     */
    fun filterProviders(apis: List<MainAPI>, isNsfwEnabled: Boolean): List<MainAPI> {
        if (isNsfwEnabled) return apis
        return apis.filterNot { isNsfwProvider(it) }
    }
}
