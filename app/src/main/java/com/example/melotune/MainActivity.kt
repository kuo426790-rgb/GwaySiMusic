package com.example.melotune

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.widget.ImageView
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private data class Song(val id: Long, val title: String, val artist: String, val uri: Uri, val albumId: Long)

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var screenContainer: FrameLayout
    private lateinit var bottomNavigation: LinearLayout
    private var currentScreen = "Library"
    private lateinit var libraryScreen: LinearLayout
    private lateinit var playerScreen: LinearLayout
    private lateinit var equalizerScreen: LinearLayout
    private lateinit var songList: LinearLayout
    private lateinit var nowPlaying: TextView
    private lateinit var playerArtwork: ImageView
    private lateinit var eqArea: LinearLayout
    private lateinit var playbackSeek: SeekBar
    private lateinit var playbackTime: TextView
    private lateinit var playbackDuration: TextView
    private lateinit var playPauseButton: Button
    private lateinit var shuffleButton: Button
    private lateinit var repeatButton: Button
    private var shuffleEnabled = false
    private var repeatEnabled = false
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressUpdater = object : Runnable {
        override fun run() {
            val active = player
            if (active != null) {
                try {
                    if (!playbackSeek.isPressed && active.duration > 0) playbackSeek.progress = active.currentPosition.coerceIn(0, playbackSeek.max)
                    playbackTime.text = formatTime(active.currentPosition)
                    playbackDuration.text = formatTime(active.duration)
                    playPauseButton.text = if (active.isPlaying) "Ⅱ" else "▶"
                } catch (_: Exception) { }
            }
            progressHandler.postDelayed(this, 500)
        }
    }
    private lateinit var search: EditText
    private val songs = mutableListOf<Song>()
    private var selectedMood = "All"
    private val moodPrefs by lazy { getSharedPreferences("custom_moods", MODE_PRIVATE) }
    private val defaultMoods = listOf("All", "Sad", "Chill", "Love", "Happy", "Gaming", "Rap", "Phonk", "Drill")
    private val moodAssignments = mutableMapOf<String, MutableSet<Long>>()
    private lateinit var moodRow: LinearLayout
    private var player: MediaPlayer? = null
    private var equalizer: Equalizer? = null
    private var currentSong: Song? = null
    private val prefs by lazy { getSharedPreferences("song_eq_settings", MODE_PRIVATE) }
    private val bandNames = listOf("60 Hz", "230 Hz", "910 Hz", "3.6 kHz", "14 kHz")
    private var bandRange: ShortArray = shortArrayOf(-1500, 1500)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestAudioPermission()
    }


    private fun allMoods(): List<String> {
        val custom = moodPrefs.getString("custom_names", "[]") ?: "[]"
        val names = mutableListOf<String>()
        try {
            val array = JSONArray(custom)
            for (i in 0 until array.length()) {
                val name = array.optString(i).trim()
                if (name.isNotEmpty() && name !in defaultMoods && name !in names) {
                    names.add(name)
                }
            }
        } catch (_: Exception) {}
        return defaultMoods + names
    }

    private fun loadMoodAssignments() {
        moodAssignments.clear()
        try {
            val saved = JSONObject(moodPrefs.getString("assignments", "{}") ?: "{}")
            val keys = saved.keys()
            while (keys.hasNext()) {
                val mood = keys.next()
                val ids = saved.optJSONArray(mood) ?: JSONArray()
                val set = mutableSetOf<Long>()
                for (i in 0 until ids.length()) {
                    set.add(ids.optLong(i))
                }
                moodAssignments[mood] = set
            }
        } catch (_: Exception) {}
    }

    private fun saveMoodAssignments() {
        val saved = JSONObject()
        moodAssignments.forEach { (mood, ids) ->
            val array = JSONArray()
            ids.forEach { array.put(it) }
            saved.put(mood, array)
        }
        moodPrefs.edit().putString("assignments", saved.toString()).apply()
    }

    private fun refreshMoodButtons() {
        moodRow.removeAllViews()
        allMoods().forEach { mood ->
            val button = Button(this).apply {
                text = mood
                isAllCaps = false
                setOnClickListener {
                    selectedMood = mood
                    refreshMoodButtons()
                    renderSongList(search.text.toString())
                }
            }
            stylePremiumButton(button, selectedMood == mood, false)
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(44)
            )
            params.marginEnd = dp(8)
            moodRow.addView(button, params)
        }
    }

    private fun showMoodManager() {
        val actions = arrayOf("Create mood", "Rename selected custom mood", "Delete selected custom mood")
        AlertDialog.Builder(this)
            .setTitle("Manage moods")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> promptMoodName("Create mood") { name ->
                        if (name in allMoods()) {
                            Toast.makeText(this, "Mood already exists", Toast.LENGTH_SHORT).show()
                        } else {
                            val custom = JSONArray()
                            allMoods().filter { it !in defaultMoods }.forEach { custom.put(it) }
                            custom.put(name)
                            moodPrefs.edit().putString("custom_names", custom.toString()).apply()
                            moodAssignments[name] = mutableSetOf()
                            saveMoodAssignments()
                            selectedMood = name
                            refreshMoodButtons()
                            renderSongList(search.text.toString())
                        }
                    }
                    1 -> {
                        if (selectedMood in defaultMoods) {
                            Toast.makeText(this, "Select a custom mood first", Toast.LENGTH_SHORT).show()
                        } else {
                            promptMoodName("Rename $selectedMood") { newName ->
                                if (newName in allMoods()) {
                                    Toast.makeText(this, "Mood already exists", Toast.LENGTH_SHORT).show()
                                } else {
                                    val oldName = selectedMood
                                    val custom = JSONArray()
                                    allMoods().filter { it !in defaultMoods && it != oldName }
                                        .forEach { custom.put(it) }
                                    custom.put(newName)
                                    moodPrefs.edit().putString("custom_names", custom.toString()).apply()
                                    moodAssignments.remove(oldName)?.let { moodAssignments[newName] = it }

                                    val manual = try {
                                        val array = JSONArray(moodPrefs.getString("manual_moods", "[]") ?: "[]")
                                        (0 until array.length()).map { array.optString(it) }.toMutableSet()
                                    } catch (_: Exception) {
                                        mutableSetOf<String>()
                                    }
                                    if (manual.remove(oldName)) manual.add(newName)
                                    val savedManual = JSONArray()
                                    manual.forEach { savedManual.put(it) }
                                    moodPrefs.edit().putString("manual_moods", savedManual.toString()).apply()
                                    saveMoodAssignments()
                                    selectedMood = newName
                                    refreshMoodButtons()
                                    renderSongList(search.text.toString())
                                }
                            }
                        }
                    }
                    2 -> {
                        if (selectedMood in defaultMoods) {
                            Toast.makeText(this, "Select a custom mood first", Toast.LENGTH_SHORT).show()
                        } else {
                            val oldName = selectedMood
                            AlertDialog.Builder(this)
                                .setTitle("Delete $oldName?")
                                .setMessage("This removes the mood and its song assignments.")
                                .setNegativeButton("Cancel", null)
                                .setPositiveButton("Delete") { _, _ ->
                                    val custom = JSONArray()
                                    allMoods().filter { it !in defaultMoods && it != oldName }
                                        .forEach { custom.put(it) }
                                    moodPrefs.edit().putString("custom_names", custom.toString()).apply()
                                    moodAssignments.remove(oldName)

                                    val manual = try {
                                        val array = JSONArray(moodPrefs.getString("manual_moods", "[]") ?: "[]")
                                        (0 until array.length()).map { array.optString(it) }.toMutableSet()
                                    } catch (_: Exception) {
                                        mutableSetOf<String>()
                                    }
                                    manual.remove(oldName)
                                    val savedManual = JSONArray()
                                    manual.forEach { savedManual.put(it) }
                                    moodPrefs.edit().putString("manual_moods", savedManual.toString()).apply()
                                    saveMoodAssignments()
                                    selectedMood = "All"
                                    refreshMoodButtons()
                                    renderSongList(search.text.toString())
                                }
                                .show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptMoodName(title: String, onSave: (String) -> Unit) {
        val input = EditText(this).apply {
            hint = "Mood name"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank() || name == "All") {
                    Toast.makeText(this, "Enter a valid mood name", Toast.LENGTH_SHORT).show()
                } else {
                    onSave(name)
                }
            }
            .show()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
            setBackgroundColor(0xFF0B0A10.toInt())
        }

        screenContainer = FrameLayout(this)
        root.addView(
            screenContainer,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        libraryScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.VISIBLE
        }
        playerScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        equalizerScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        screenContainer.addView(libraryScreen, FrameLayout.LayoutParams(-1, -1))
        screenContainer.addView(playerScreen, FrameLayout.LayoutParams(-1, -1))
        screenContainer.addView(equalizerScreen, FrameLayout.LayoutParams(-1, -1))

        // LIBRARY SCREEN
        val libraryScroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        val libraryContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(16))
        }
        libraryScroll.addView(libraryContent)
        libraryScreen.addView(libraryScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val title = TextView(this).apply {
            text = "GwaySi Music  ♫"
            textSize = 28f
            setTextColor(0xFFD0B5FF.toInt())
            gravity = Gravity.CENTER_VERTICAL
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(2))
        }
        libraryContent.addView(title)

        val subtitle = TextView(this).apply {
            text = "YOUR SOUND. YOUR MOOD."
            textSize = 10f
            letterSpacing = 0.16f
            setTextColor(0xFF9C91B5.toInt())
            setPadding(0, 0, 0, dp(18))
        }
        libraryContent.addView(subtitle)

        val moodTitle = TextView(this).apply {
            text = "PICK YOUR MOOD  ✨"
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFFD0B5FF.toInt())
            setPadding(0, 0, 0, dp(10))
        }
        libraryContent.addView(moodTitle)

        val moodScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        moodRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        loadMoodAssignments()
        refreshMoodButtons()
        moodScroll.addView(moodRow)
        libraryContent.addView(moodScroll)

        val manageMoods = Button(this).apply {
            text = "＋ Create / Manage Moods"
            setOnClickListener { showMoodManager() }
        }
        stylePremiumButton(manageMoods, false, false)
        libraryContent.addView(manageMoods, matchWrap())

        search = EditText(this).apply {
            hint = "⌕  Search your music..."
            textSize = 15f
            setSingleLine(true)
            setTextColor(0xFFF4F0FF.toInt())
            setHintTextColor(0xFF898497.toInt())
            setPadding(dp(15), dp(5), dp(15), dp(5))
            background = roundedBackground(0xFF191622.toInt(), 16)
        }
        val searchParams = matchWrap().apply {
            topMargin = dp(18)
            bottomMargin = dp(10)
        }
        libraryContent.addView(search, searchParams)

        val yt = Button(this).apply {
            text = "Search on YouTube  ↗"
            textSize = 14f
            setOnClickListener { openYouTubeSearch() }
        }
        stylePremiumButton(yt, false, false)
        libraryContent.addView(yt, matchWrap())

        val listTitle = TextView(this).apply {
            text = "YOUR MUSIC"
            textSize = 17f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFFD0B5FF.toInt())
            setPadding(0, dp(22), 0, dp(12))
        }
        libraryContent.addView(listTitle)

        songList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        libraryContent.addView(songList)

        search.addTextChangedListener(SimpleTextWatcher {
            renderSongList(it)
        })

        // PLAYER SCREEN
        val playerScroll = ScrollView(this).apply {
            isFillViewport = true
        }
        val playerContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(24), dp(8), dp(24))
        }
        playerScroll.addView(playerContent)
        playerScreen.addView(playerScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val playerHeading = TextView(this).apply {
            text = "NOW PLAYING"
            textSize = 12f
            letterSpacing = 0.16f
            setTextColor(0xFFA994D8.toInt())
            gravity = Gravity.CENTER
        }
        playerContent.addView(playerHeading, matchWrap())

        playerArtwork = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(android.R.drawable.ic_media_play)
            background = roundedBackground(0xFF191622.toInt(), 28)
            clipToOutline = true
            contentDescription = "Now playing album artwork"
        }
        val artworkParams = LinearLayout.LayoutParams(-1, dp(280))
        artworkParams.setMargins(dp(8), dp(28), dp(8), dp(24))
        playerContent.addView(playerArtwork, artworkParams)

        nowPlaying = TextView(this).apply {
            text = "Choose a song to start"
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(0xFFF4F0FF.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        playerContent.addView(nowPlaying, matchWrap())

        val playerHint = TextView(this).apply {
            text = "YOUR SOUND. YOUR MOOD."
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFF9C91B5.toInt())
            setPadding(0, 0, 0, dp(20))
        }
        playerContent.addView(playerHint)

        playbackSeek = SeekBar(this).apply {
            max = 1000
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(0xFFD0B5FF.toInt())
            thumbTintList = android.content.res.ColorStateList.valueOf(0xFFD0B5FF.toInt())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) playbackTime.text = formatTime(progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val active = player ?: return
                    try { active.seekTo(seekBar?.progress ?: 0) } catch (_: Exception) { }
                }
            })
        }
        playerContent.addView(playbackSeek, matchWrap())
        val timeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        playbackTime = TextView(this).apply { text = "0:00"; textSize = 11f; setTextColor(0xFFAAA2BA.toInt()) }
        playbackDuration = TextView(this).apply { text = "0:00"; textSize = 11f; gravity = Gravity.END; setTextColor(0xFFAAA2BA.toInt()) }
        timeRow.addView(playbackTime, LinearLayout.LayoutParams(0, dp(24), 1f))
        timeRow.addView(playbackDuration, LinearLayout.LayoutParams(0, dp(24), 1f))
        playerContent.addView(timeRow, matchWrap())

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        val previous = Button(this).apply { text = "|◀"; textSize = 18f; setOnClickListener { playRelative(-1) } }
        stylePremiumButton(previous, false, true)
        playPauseButton = Button(this).apply { text = "▶"; textSize = 23f; setOnClickListener { togglePlayback() } }
        stylePremiumButton(playPauseButton, true, true)
        val next = Button(this).apply { text = "▶|"; textSize = 18f; setOnClickListener { playRelative(1) } }
        stylePremiumButton(next, false, true)
        val sideParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(8) }
        controls.addView(previous, sideParams)
        controls.addView(playPauseButton, LinearLayout.LayoutParams(0, dp(56), 1.2f).apply { marginEnd = dp(8) })
        controls.addView(next, LinearLayout.LayoutParams(0, dp(52), 1f))
        playerContent.addView(controls)

        val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        shuffleButton = Button(this).apply {
            text = "⇄  Shuffle OFF"
            setOnClickListener { shuffleEnabled = !shuffleEnabled; text = if (shuffleEnabled) "⇄  Shuffle ON" else "⇄  Shuffle OFF"; stylePremiumButton(this, shuffleEnabled, true) }
        }
        repeatButton = Button(this).apply {
            text = "↻  Repeat OFF"
            setOnClickListener { repeatEnabled = !repeatEnabled; text = if (repeatEnabled) "↻  Repeat ON" else "↻  Repeat OFF"; stylePremiumButton(this, repeatEnabled, true) }
        }
        stylePremiumButton(shuffleButton, false, true); stylePremiumButton(repeatButton, false, true)
        modes.addView(shuffleButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(8) })
        modes.addView(repeatButton, LinearLayout.LayoutParams(0, dp(42), 1f))
        playerContent.addView(modes, matchWrap())

        val openEq = Button(this).apply {
            text = "♫  Open Equalizer"
            setOnClickListener {
                if (eqArea.visibility == View.VISIBLE) {
                    eqArea.visibility = View.GONE
                    text = "♫  Open Equalizer"
                } else {
                    eqArea.visibility = View.VISIBLE
                    text = "♫  Hide Equalizer"
                    buildEqualizerUi()
                }
            }
        }
        stylePremiumButton(openEq, true, false)
        playerContent.addView(openEq, matchWrap())

        // EQUALIZER SCREEN
        val eqScroll = ScrollView(this).apply {
            isFillViewport = true
        }
        val eqContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(20))
        }
        eqScroll.addView(eqContent)
        equalizerScreen.addView(eqScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val eqTitle = TextView(this).apply {
            text = "EQUALIZER  ♪"
            textSize = 23f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFFD0B5FF.toInt())
            setPadding(0, dp(12), 0, dp(8))
        }
        eqContent.addView(eqTitle)

        val info = TextView(this).apply {
            text = "Customize the sound for each song. Your settings are saved."
            textSize = 12f
            setTextColor(0xFFB0A8C0.toInt())
            setPadding(0, 0, 0, dp(16))
        }
        eqContent.addView(info)

        eqArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedBackground(0xFF17141F.toInt(), 16)
            visibility = View.GONE
        }
        playerContent.addView(eqArea, matchWrap())

        // BOTTOM NAVIGATION
        bottomNavigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(6), dp(4), dp(4))
            background = roundedBackground(0xFF17141F.toInt(), 20)
        }
        val navItems = listOf(
            "Library" to "♫\nLibrary",
            "Player" to "▶\nPlayer"
        )
        navItems.forEach { (screen, label) ->
            val button = Button(this).apply {
                text = label
                textSize = 11f
                isAllCaps = false
                setPadding(dp(2), dp(4), dp(2), dp(4))
                setOnClickListener { showScreen(screen) }
            }
            val params = LinearLayout.LayoutParams(0, dp(56), 1f)
            params.marginEnd = dp(4)
            bottomNavigation.addView(button, params)
        }
        root.addView(bottomNavigation, LinearLayout.LayoutParams(-1, dp(64)))
        setContentView(root)
        progressHandler.post(progressUpdater)
        showScreen("Library")
    }

    private fun showScreen(screen: String) {
        currentScreen = screen
        if (!::libraryScreen.isInitialized ||
            !::playerScreen.isInitialized ||
            !::equalizerScreen.isInitialized
        ) return

        libraryScreen.visibility = if (screen == "Library") View.VISIBLE else View.GONE
        playerScreen.visibility = if (screen == "Player") View.VISIBLE else View.GONE
        equalizerScreen.visibility = if (screen == "Equalizer") View.VISIBLE else View.GONE

        if (::bottomNavigation.isInitialized) {
            for (i in 0 until bottomNavigation.childCount) {
                val button = bottomNavigation.getChildAt(i) as? Button ?: continue
                val selected = when (i) {
                    0 -> screen == "Library"
                    1 -> screen == "Player"
                    else -> screen == "Equalizer"
                }
                stylePremiumButton(button, selected, false)
            }
        }
    }

    private fun requestAudioPermission() {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) loadSongs()
        else requestPermissions(arrayOf(permission), 41)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) loadSongs()
        else Toast.makeText(this, "Allow music access to show songs on your phone.", Toast.LENGTH_LONG).show()
    }

    private fun loadSongs() {
        songs.clear()
        val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM_ID)
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        contentResolver.query(collection, projection, selection, null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val songUri = ContentUris.withAppendedId(collection, id)
                songs.add(Song(id, cursor.getString(titleCol) ?: "Unknown title", cursor.getString(artistCol) ?: "Unknown artist", songUri, cursor.getLong(albumIdCol)))
            }
        }
        renderSongList(search.text.toString())
        if (songs.isEmpty()) Toast.makeText(this, "No local music found. Add audio files to your phone first.", Toast.LENGTH_LONG).show()
    }

    private fun renderSongList(query: String) {
        if (!::songList.isInitialized) return
        songList.removeAllViews()

        val moodWords = when (selectedMood) {
            "Sad" -> listOf("sad", "cry", "heartbreak", "lonely", "rain", "pain", "depress")
            "Chill" -> listOf("chill", "lofi", "relax", "calm", "acoustic", "ambient")
            "Love" -> listOf("love", "lover", "romance", "heart", "kiss")
            "Happy" -> listOf("happy", "joy", "party", "fun", "smile", "dance")
            "Gaming" -> listOf("gaming", "phonk", "trap", "epic", "battle", "war", "pubg")
            "Rap" -> listOf("rap", "hip hop", "hip-hop", "rapper")
            "Phonk" -> listOf("phonk", "drift", "cowbell")
            "Drill" -> listOf("drill", "uk drill", "ny drill")
            else -> emptyList()
        }

        val searchFiltered = songs.filter { song ->
            song.title.contains(query, true) ||
                song.artist.contains(query, true)
        }

        val manualMoods = try {
            val array = JSONArray(moodPrefs.getString("manual_moods", "[]") ?: "[]")
            (0 until array.length()).map { array.optString(it) }.toSet()
        } catch (_: Exception) {
            emptySet<String>()
        }

        val filtered = when {
            selectedMood == "All" -> searchFiltered
            selectedMood in manualMoods || selectedMood !in defaultMoods ->
                searchFiltered.filter { song ->
                    moodAssignments[selectedMood]?.contains(song.id) == true
                }
            else -> searchFiltered.filter { song ->
                moodWords.any { word ->
                    song.title.contains(word, true) ||
                        song.artist.contains(word, true)
                }
            }
        }

        if (filtered.isEmpty()) {
            val empty = TextView(this).apply {
                text = if (songs.isEmpty()) {
                    "No music found on this phone yet."
                } else if (selectedMood != "All") {
                    "No matching songs for $selectedMood. Try All or search another song."
                } else {
                    "No songs match your search."
                }
                textSize = 13f
                setTextColor(0xFFB0A8C0.toInt())
                setPadding(dp(16), dp(18), dp(16), dp(18))
                background = roundedBackground(0xFF17141F.toInt(), 16)
            }
            songList.addView(empty, matchWrap())
            return
        }

        filtered.forEachIndexed { index, song ->
            val selected = currentSong?.id == song.id
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(10), dp(10))
                background = roundedBackground(
                    if (selected) 0xFF302044.toInt() else 0xFF17141F.toInt(),
                    18
                )
                elevation = dp(if (selected) 3 else 1).toFloat()
                setOnClickListener { playSong(song) }
            }

            val artFrame = FrameLayout(this).apply {
                background = roundedBackground(
                    if (selected) 0xFF8060B8.toInt() else 0xFF292238.toInt(),
                    14
                )
                clipToOutline = true
                contentDescription = "Album art"
            }

            val artFallback = TextView(this).apply {
                text = if (selected) "♫" else "♪"
                textSize = 25f
                gravity = Gravity.CENTER
                setTextColor(0xFFD0B5FF.toInt())
            }
            artFrame.addView(
                artFallback,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )

            if (song.albumId > 0) {
                val albumArt = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    try {
                        val albumArtUri = ContentUris.withAppendedId(
                            Uri.parse("content://media/external/audio/albumart"),
                            song.albumId
                        )
                        setImageURI(albumArtUri)
                    } catch (_: Exception) {
                        setImageDrawable(null)
                    }
                }
                artFrame.addView(
                    albumArt,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
            }

            card.addView(artFrame, LinearLayout.LayoutParams(dp(50), dp(50)))

            val cardParams = matchWrap().apply {
                bottomMargin = dp(8)
            }

            val details = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(4), 0)
            }

            val songTitle = TextView(this).apply {
                text = song.title
                textSize = 14f
                maxLines = 2
                setTextColor(0xFFF4F0FF.toInt())
                setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
            details.addView(songTitle)

            val artist = TextView(this).apply {
                text = song.artist
                textSize = 12f
                maxLines = 1
                setTextColor(0xFFB0A8C0.toInt())
                setPadding(0, dp(3), 0, 0)
            }
            details.addView(artist)

            card.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val more = TextView(this).apply {
                text = if (selected) "♫" else "›"
                textSize = 20f
                setTextColor(0xFFD0B5FF.toInt())
                gravity = Gravity.CENTER
            }
            more.text = "⋮"
            more.contentDescription = "Assign moods"
            more.setOnClickListener {
                val availableMoods = allMoods().filter { it != "All" }
                val checked = BooleanArray(availableMoods.size) { index ->
                    moodAssignments[availableMoods[index]]?.contains(song.id) == true
                }

                AlertDialog.Builder(this)
                    .setTitle("Assign moods")
                    .setMultiChoiceItems(availableMoods.toTypedArray(), checked) { _, which, isChecked ->
                        val mood = availableMoods[which]
                        val ids = moodAssignments.getOrPut(mood) { mutableSetOf() }
                        if (isChecked) ids.add(song.id) else ids.remove(song.id)

                        val manual = try {
                            val array = JSONArray(moodPrefs.getString("manual_moods", "[]") ?: "[]")
                            (0 until array.length()).map { array.optString(it) }.toMutableSet()
                        } catch (_: Exception) {
                            mutableSetOf<String>()
                        }
                        manual.add(mood)
                        val savedManual = JSONArray()
                        manual.forEach { savedManual.put(it) }
                        moodPrefs.edit().putString("manual_moods", savedManual.toString()).apply()
                        saveMoodAssignments()
                    }
                    .setPositiveButton("Done") { _, _ ->
                        renderSongList(search.text.toString())
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            card.addView(more, LinearLayout.LayoutParams(dp(36), dp(44)))
            songList.addView(card, cardParams)

        }
    }

    private fun playSong(song: Song) {
        try {
            releaseAudio()
            currentSong = song
            showScreen("Player")
            player = MediaPlayer().apply {
                setDataSource(this@MainActivity, song.uri)
                setOnCompletionListener { if (repeatEnabled) { seekTo(0); start() } else playRelative(if (shuffleEnabled && songs.size > 1) (1..songs.lastIndex).random() else 1) }
                prepare()
                start()
            }
            val sessionId = player!!.audioSessionId
            equalizer = Equalizer(0, sessionId).apply { enabled = true }
            val eq = equalizer!!
            bandRange = eq.bandLevelRange
            nowPlaying.text = "${song.title}\n${song.artist}"
            playbackSeek.max = player!!.duration.coerceAtLeast(1)
            playbackSeek.progress = 0
            playbackTime.text = "0:00"
            playbackDuration.text = formatTime(player!!.duration)
            playPauseButton.text = "Ⅱ"
            try {
                if (song.albumId > 0) {
                    val albumArtUri = ContentUris.withAppendedId(
                        Uri.parse("content://media/external/audio/albumart"),
                        song.albumId
                    )
                    playerArtwork.setImageURI(albumArtUri)
                } else {
                    playerArtwork.setImageResource(android.R.drawable.ic_media_play)
                }
            } catch (_: Exception) {
                playerArtwork.setImageResource(android.R.drawable.ic_media_play)
            }
            buildEqualizerUi()
            renderSongList(search.text.toString())
            applySavedEq(song)
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't play this file: ${e.localizedMessage ?: "unknown error"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildEqualizerUi() {
        eqArea.removeAllViews()
        val eq = equalizer ?: return
        val count = minOf(eq.numberOfBands.toInt(), bandNames.size)
        for (i in 0 until count) {
            val band = i.toShort()
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val label = TextView(this).apply { text = bandNames[i]; textSize = 12f; setTextColor(0xFFE6E2F2.toInt()) }
            row.addView(label, LinearLayout.LayoutParams(dp(62), ViewGroup.LayoutParams.WRAP_CONTENT))
            val seek = SeekBar(this)
            seek.max = (bandRange[1] - bandRange[0]).toInt()
            seek.progress = (eq.getBandLevel(band) - bandRange[0]).toInt()
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        try { equalizer?.setBandLevel(band, (progress + bandRange[0]).toShort()); saveCurrentEq() } catch (_: Exception) { }
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            row.addView(seek, LinearLayout.LayoutParams(0, dp(42), 1f))
            eqArea.addView(row)
        }
        val reset = Button(this).apply {
            text = "Reset this song's EQ"
            setOnClickListener {
                val active = equalizer ?: return@setOnClickListener
                for (i in 0 until active.numberOfBands) active.setBandLevel(i.toShort(), 0)
                saveCurrentEq(); buildEqualizerUi()
            }
        }
        eqArea.addView(reset)
    }

    private fun prefsKey(song: Song) = "song_${song.id}"

    private fun saveCurrentEq() {
        val song = currentSong ?: return
        val eq = equalizer ?: return
        val values = JSONArray()
        for (i in 0 until eq.numberOfBands) values.put(eq.getBandLevel(i.toShort()).toInt())
        prefs.edit().putString(prefsKey(song), values.toString()).apply()
    }

    private fun applySavedEq(song: Song) {
        val eq = equalizer ?: return
        val saved = prefs.getString(prefsKey(song), null) ?: return
        try {
            val values = JSONArray(saved)
            for (i in 0 until minOf(values.length(), eq.numberOfBands.toInt())) {
                val level = values.getInt(i).coerceIn(bandRange[0].toInt(), bandRange[1].toInt())
                eq.setBandLevel(i.toShort(), level.toShort())
            }
            buildEqualizerUi()
        } catch (_: Exception) { }
    }

    private fun togglePlayback() {
        val p = player
        if (p == null) {
            if (songs.isNotEmpty()) playSong(songs.first()) else loadSongs()
            return
        }
        if (p.isPlaying) p.pause() else p.start()
    }

    private fun playRelative(delta: Int) {
        if (songs.isEmpty()) return
        val currentIndex = songs.indexOfFirst { it.id == currentSong?.id }.let { if (it < 0) 0 else it }
        val next = if (delta > 0 && shuffleEnabled && songs.size > 1) { (songs.indices.filter { it != currentIndex }).random() } else (currentIndex + delta + songs.size) % songs.size
        playSong(songs[next])
    }

    private fun openYouTubeSearch() {
        val query = search.text.toString().trim()
        if (query.isBlank()) {
            Toast.makeText(this, "Type a song name in the search box first.", Toast.LENGTH_SHORT).show()
            return
        }
        val url = "https://www.youtube.com/results?search_query=" + Uri.encode(query)
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun releaseAudio() {
        try { equalizer?.enabled = false; equalizer?.release() } catch (_: Exception) { }
        equalizer = null
        try { player?.stop(); player?.release() } catch (_: Exception) { }
        player = null
    }

    override fun onDestroy() { progressHandler.removeCallbacks(progressUpdater); saveCurrentEq(); releaseAudio(); super.onDestroy() }

    private fun formatTime(milliseconds: Int): String {
        val totalSeconds = (milliseconds / 1000).coerceAtLeast(0)
        return String.format(Locale.getDefault(), "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }
    private fun roundedBackground(color: Int, radiusDp: Int): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
        }
    }

    private fun stylePremiumButton(button: Button, selected: Boolean, pill: Boolean) {
        button.isAllCaps = false
        button.setTextColor(if (selected) 0xFF21172F.toInt() else 0xFFE8E1F5.toInt())
        button.textSize = if (pill) 12f else 13f
        button.setPadding(dp(14), dp(4), dp(14), dp(4))
        button.minHeight = dp(if (pill) 42 else 46)
        button.minimumHeight = dp(if (pill) 42 else 46)
        button.stateListAnimator = null
        button.background = roundedBackground(
            if (selected) 0xFFD0B5FF.toInt() else 0xFF292633.toInt(),
            if (pill) 24 else 16
        )
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun matchWrap() = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun weightWrap() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
}

private class SimpleTextWatcher(private val changed: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed(s?.toString() ?: "") }
    override fun afterTextChanged(s: android.text.Editable?) {}
}
