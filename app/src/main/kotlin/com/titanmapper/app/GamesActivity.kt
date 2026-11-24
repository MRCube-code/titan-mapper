package com.titanmapper.app

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.titanmapper.app.databinding.ActivityGamesBinding
import com.titanmapper.app.databinding.ItemGameTileBinding
import com.titanmapper.core.data.repository.DeviceRepository
import com.titanmapper.core.data.repository.ProfileRepository
import com.titanmapper.core.model.DeviceClass
import com.titanmapper.core.model.MappingProfile
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * "Games" screen, matching the reference screenshot's layout (device status chips, a big
 * selected-game tile, "+ Add Game"). Per the user's decision, the reference's Account/Lottery
 * bottom-nav tabs are dropped entirely rather than built as empty placeholders -- this app has
 * no login system or rewards/currency system for them to mean anything real.
 *
 * "Adding a game" doesn't need a new data model: a game *is* a [MappingProfile] with a non-null
 * [MappingProfile.targetPackageName] -- this screen is a friendlier presentation over the
 * existing [ProfileRepository], and "Start Game" just launches that app; the already-existing
 * [com.titanmapper.core.model.ProfileResolver] auto-activates the matching profile once that
 * package becomes the foreground app (see the "Automatic profile switching" setting), so no new
 * engine logic is needed either.
 */
@AndroidEntryPoint
class GamesActivity : ComponentActivity() {

    @Inject lateinit var profileRepository: ProfileRepository
    @Inject lateinit var deviceRepository: DeviceRepository

    private lateinit var binding: ActivityGamesBinding
    private var games: List<MappingProfile> = emptyList()
    private var selectedGame: MappingProfile? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGamesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.addGameButton.setOnClickListener { showAppPickerDialog() }
        binding.selectedGameTile.setOnClickListener { showAppPickerDialog() }
        binding.startGameButton.setOnClickListener { launchSelectedGame() }
        binding.navSettings.setOnClickListener { finish() } // Settings already lives one tap away on the main screen.

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    profileRepository.observeProfiles().collect { profiles ->
                        games = profiles.filter { it.targetPackageName != null }
                        renderGames()
                    }
                }
                launch {
                    deviceRepository.observeDevices().collect { devices ->
                        val mouseConnected = devices.any { it.connected && it.fingerprint.deviceClass in MOUSE_CLASSES }
                        val keyboardConnected = devices.any { it.connected && it.fingerprint.deviceClass in KEYBOARD_CLASSES }
                        setChip(binding.mouseStatusLabel, mouseConnected)
                        setChip(binding.keyboardStatusLabel, keyboardConnected)
                    }
                }
            }
        }
    }

    private fun setChip(label: android.widget.TextView, connected: Boolean) {
        label.text = getString(if (connected) R.string.games_connected else R.string.games_disconnected)
    }

    /**
     * Renders the added-games row and keeps [selectedGame] valid. This used to call [selectGame]
     * whenever nothing was selected, and [selectGame] called straight back into [renderGames] --
     * with zero games added (the default state on first open), that pair called each other
     * forever and stack-overflowed the app ("Titan Mapper keeps stopping", reported against a
     * fresh/empty Games screen). Fixed by having this function own reassigning [selectedGame]
     * directly, with [selectGame] now only reachable from a real user tap on an existing tile.
     */
    private fun renderGames() {
        binding.addedGamesRow.removeAllViews()
        games.forEach { game ->
            val tile = ItemGameTileBinding.inflate(LayoutInflater.from(this), binding.addedGamesRow, false)
            tile.gameTileLabel.text = game.name
            tile.gameTileIcon.setImageDrawable(appIconOrNull(game.targetPackageName) ?: getDrawable(R.drawable.ic_titan_gamepad))
            tile.gameTileIcon.setBackgroundResource(
                if (game.id == selectedGame?.id) R.drawable.bg_titan_tile_selected else R.drawable.bg_titan_tile
            )
            tile.root.setOnClickListener { selectGame(game) }
            binding.addedGamesRow.addView(tile.root)
        }
        if (selectedGame == null || games.none { it.id == selectedGame?.id }) {
            selectedGame = games.firstOrNull()
        }
        renderSelectedTile()
    }

    private fun selectGame(game: MappingProfile) {
        selectedGame = game
        renderGames()
    }

    private fun renderSelectedTile() {
        val game = selectedGame
        binding.selectedGameTile.setImageDrawable(
            appIconOrNull(game?.targetPackageName) ?: getDrawable(R.drawable.ic_titan_gamepad)
        )
        binding.startGameButton.isEnabled = game != null
    }

    private fun launchSelectedGame() {
        val packageName = selectedGame?.targetPackageName ?: return
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            Toast.makeText(this, getString(R.string.games_no_launchable_apps), Toast.LENGTH_SHORT).show()
        }
    }

    /** Installed, launchable, non-system(ish) apps -- a simple real app picker, no fake data. */
    private fun showAppPickerDialog() {
        val launchables = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != packageName } // Don't let the user add Titan Mapper itself.
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }

        if (launchables.isEmpty()) {
            Toast.makeText(this, getString(R.string.games_no_launchable_apps), Toast.LENGTH_SHORT).show()
            return
        }

        val labels = launchables.map { it.loadLabel(packageManager).toString() }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.games_add_game)
            .setItems(labels) { _, index -> addGame(launchables[index]) }
            .show()
    }

    private fun addGame(appInfo: ApplicationInfo) {
        val label = appInfo.loadLabel(packageManager).toString()
        val profile = MappingProfile(
            id = UUID.randomUUID().toString(),
            name = label,
            targetPackageName = appInfo.packageName,
            isActive = true
        )
        lifecycleScope.launch {
            profileRepository.save(profile)
        }
    }

    private fun appIconOrNull(packageName: String?): android.graphics.drawable.Drawable? {
        if (packageName == null) return null
        return try {
            packageManager.getApplicationIcon(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    private companion object {
        val MOUSE_CLASSES = setOf(DeviceClass.MOUSE, DeviceClass.HYBRID)
        val KEYBOARD_CLASSES = setOf(DeviceClass.KEYBOARD, DeviceClass.HYBRID)
    }
}
