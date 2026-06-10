package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.List
import androidx.compose.foundation.lazy.LazyColumn
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.Close

enum class CellType { EMPTY, SUN, MOON }
enum class ConstraintType { EQUALS, CROSS }
data class TangoConstraint(val row: Int, val col: Int, val type: ConstraintType)

data class PendingHintInfo(
    val row: Int,
    val col: Int,
    val change: CellType,
    val sources: Set<Pair<Int, Int>>,
    val explanation: String
)

data class TraceStep(
    val r: Int,
    val c: Int,
    val type: CellType,
    val reason: String
)

data class TangoLevel(
    val id: Int,
    val levelNumber: Int,
    val solution: List<List<CellType>>,
    val initial: List<List<CellType>>,
    val vConstraints: List<TangoConstraint>,
    val hConstraints: List<TangoConstraint>,
    val shadedCells: Set<Pair<Int, Int>>
)

enum class Difficulty(val label: String, val color: Color, val description: String) {
    EASY("Easy", Color(0xFF4CAF50), "Basic Sandwich and Pair rules required."),
    MEDIUM("Medium", Color(0xFF1976D2), "Requires basic rules and constraint-matching."),
    HARD("Hard", Color(0xFFF57C00), "Requires advanced Balance and LOOKAHEAD tracing."),
    EXPERT("Expert", Color(0xFF9C27B0), "Requires deep lookahead logic and multi-step contradiction chains.")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                TangoApp()
            }
        }
    }
}

class TangoViewModel(application: android.app.Application) : androidx.lifecycle.AndroidViewModel(application) {
    val prefs = application.getSharedPreferences("tango_prefs", android.content.Context.MODE_PRIVATE)

    var currentLevelNumber by mutableIntStateOf(prefs.getInt("current_level", 1))
        private set

    fun getLevelSeed(levelNum: Int): Int {
        val key = "level_seed_$levelNum"
        val cached = prefs.getInt(key, 0)
        return if (cached != 0) {
            cached
        } else {
            val newSeed = kotlin.random.Random.nextInt(1, 1000000)
            prefs.edit().putInt(key, newSeed).apply()
            newSeed
        }
    }

    var currentLevel by mutableStateOf<TangoLevel>(LevelGenerator.generateLevel(getLevelSeed(currentLevelNumber)))
        private set
        
    var grid by mutableStateOf<List<List<CellType>>>(emptyList())
    var moveHistory = mutableListOf<List<List<CellType>>>()
    
    var timeSpent by mutableLongStateOf(0L)
    var isWon by mutableStateOf(false)
    var hintCount by mutableIntStateOf(0)
    
    var activeHint by mutableStateOf<PendingHintInfo?>(null)
        private set
    var hintExplanation by mutableStateOf("")
        private set
    var targetCell by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    var sourceCells by mutableStateOf<Set<Pair<Int, Int>>>(emptySet())
        private set
    
    var showWinOverlay by mutableStateOf(false)
    
    var soundEnabled by mutableStateOf(prefs.getBoolean("sound_enabled", true))
        private set

    var bestTime by mutableLongStateOf(0L)
        private set

    var lastClickedCell by mutableStateOf<Pair<Int, Int>?>(null)
    var lastClickTime by mutableLongStateOf(0L)

    fun getActiveGrid(): List<List<CellType>> {
        val current = lastClickedCell
        if (current != null) {
            val now = System.currentTimeMillis()
            if (now - lastClickTime < 1200) {
                return grid.mapIndexed { r, row ->
                    row.mapIndexed { c, type ->
                        if (current.first == r && current.second == c) CellType.EMPTY else type
                    }
                }
            }
        }
        return grid
    }

    fun getTripleViolationCells(): Set<Pair<Int, Int>> {
        val cells = mutableSetOf<Pair<Int, Int>>()
        val activeGrid = getActiveGrid()
        
        // Horizontal check
        for (r in 0 until 6) {
            for (c in 0 until 4) {
                if (activeGrid.size > r && activeGrid[r].size > c + 2) {
                    val c1 = activeGrid[r][c]
                    val c2 = activeGrid[r][c + 1]
                    val c3 = activeGrid[r][c + 2]
                    if (c1 != CellType.EMPTY && c1 == c2 && c2 == c3) {
                        cells.add(Pair(r, c))
                        cells.add(Pair(r, c + 1))
                        cells.add(Pair(r, c + 2))
                    }
                }
            }
        }
        
        // Vertical check
        for (c in 0 until 6) {
            for (r in 0 until 4) {
                if (activeGrid.size > r + 2 && activeGrid[r].size > c) {
                    val r1 = activeGrid[r][c]
                    val r2 = activeGrid[r + 1][c]
                    val r3 = activeGrid[r + 2][c]
                    if (r1 != CellType.EMPTY && r1 == r2 && r2 == r3) {
                        cells.add(Pair(r, c))
                        cells.add(Pair(r + 1, c))
                        cells.add(Pair(r + 2, c))
                    }
                }
            }
        }
        return cells
    }

    fun getLevelDifficulty(): Difficulty {
        val levelNum = currentLevelNumber
        val givens = currentLevel.initial.sumOf { row -> row.count { it != CellType.EMPTY } }
        val consSize = currentLevel.vConstraints.size + currentLevel.hConstraints.size
        
        return when {
            levelNum <= 1 -> Difficulty.EASY
            levelNum <= 4 -> {
                if (givens >= 12) Difficulty.EASY
                else Difficulty.MEDIUM
            }
            levelNum <= 8 -> {
                if (givens >= 10 && consSize >= 4) Difficulty.MEDIUM
                else Difficulty.HARD
            }
            else -> {
                if (givens >= 8 && consSize <= 2) Difficulty.HARD
                else Difficulty.EXPERT
            }
        }
    }
        
    fun playSound(freq: Double, durationMs: Int) {
        // Disabled to prevent AppOps attributionTag spam
    }
    
    fun toggleSound() {
        soundEnabled = !soundEnabled
        prefs.edit().putBoolean("sound_enabled", soundEnabled).apply()
    }
    
    private var timerJob: kotlinx.coroutines.Job? = null
    
    fun serializeGrid(g: List<List<CellType>>): String {
        return g.joinToString("") { row ->
            row.joinToString("") { cell ->
                when (cell) {
                    CellType.EMPTY -> "0"
                    CellType.SUN -> "1"
                    CellType.MOON -> "2"
                }
            }
        }
    }

    fun deserializeGrid(serialized: String): List<List<CellType>> {
        if (serialized.length != 36) return emptyList()
        val result = mutableListOf<List<CellType>>()
        for (r in 0 until 6) {
            val row = mutableListOf<CellType>()
            for (c in 0 until 6) {
                val char = serialized[r * 6 + c]
                row.add(when (char) {
                    '1' -> CellType.SUN
                    '2' -> CellType.MOON
                    else -> CellType.EMPTY
                })
            }
            result.add(row)
        }
        return result
    }

    fun saveGridState() {
        prefs.edit()
            .putString("saved_grid_$currentLevelNumber", serializeGrid(grid))
            .putLong("saved_time_$currentLevelNumber", timeSpent)
            .putInt("saved_hints_$currentLevelNumber", hintCount)
            .apply()
    }

    init {
        loadCurrentLevel()
    }
    
    fun loadCurrentLevel() {
        showWinOverlay = false
        clearHint()
        val saved = prefs.getString("saved_grid_$currentLevelNumber", null)
        if (saved != null && saved.length == 36) {
            grid = deserializeGrid(saved)
        } else {
            grid = currentLevel.initial.map { it.toList() }
        }
        moveHistory.clear()
        timeSpent = prefs.getLong("saved_time_$currentLevelNumber", 0L)
        bestTime = prefs.getLong("best_time_$currentLevelNumber", 0L)
        isWon = false
        hintCount = prefs.getInt("saved_hints_$currentLevelNumber", 0)
        startTimer()
    }

    fun replayLevel() {
        showWinOverlay = false
        clearHint()
        prefs.edit()
            .remove("saved_grid_$currentLevelNumber")
            .remove("saved_time_$currentLevelNumber")
            .remove("saved_hints_$currentLevelNumber")
            .apply()
        
        grid = currentLevel.initial.map { it.toList() }
        moveHistory.clear()
        timeSpent = 0L
        bestTime = prefs.getLong("best_time_$currentLevelNumber", 0L)
        isWon = false
        hintCount = 0
        startTimer()
    }
    
    fun nextLevel() {
        showWinOverlay = false
        // Remove saved state of completed level
        prefs.edit()
            .remove("saved_grid_$currentLevelNumber")
            .remove("saved_time_$currentLevelNumber")
            .remove("saved_hints_$currentLevelNumber")
            .apply()
        
        currentLevelNumber++
        prefs.edit().putInt("current_level", currentLevelNumber).apply()
        currentLevel = LevelGenerator.generateLevel(getLevelSeed(currentLevelNumber))
        loadCurrentLevel()
    }
    
    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (!isWon) {
                kotlinx.coroutines.delay(1000)
                timeSpent++
                if (timeSpent % 5 == 0L) {
                    saveGridState()
                }
            }
        }
    }
    
    fun clearHint() {
        activeHint = null
        hintExplanation = ""
        targetCell = null
        sourceCells = emptySet()
    }

    fun onCellClick(r: Int, c: Int) {
        if (isWon) return
        if (currentLevel.initial[r][c] != CellType.EMPTY) return
        
        playSound(660.0, 30)
        saveHistory()
        clearHint()
        
        // Set temporary transition lock
        lastClickedCell = Pair(r, c)
        lastClickTime = System.currentTimeMillis()
        viewModelScope.launch {
            kotlinx.coroutines.delay(1200)
            if (lastClickedCell == Pair(r, c)) {
                lastClickedCell = null
            }
        }
        
        val newGrid = grid.map { it.toMutableList() }
        newGrid[r][c] = when (grid[r][c]) {
            CellType.EMPTY -> CellType.SUN
            CellType.SUN -> CellType.MOON
            CellType.MOON -> CellType.EMPTY
        }
        grid = newGrid
        saveGridState()
        checkWin()
    }
    
    fun undo() {
        if (isWon || moveHistory.isEmpty()) return
        playSound(660.0, 30)
        clearHint()
        val last = moveHistory.removeLast()
        grid = last
        saveGridState()
        checkWin()
    }
    
    private fun getOpposite(c: CellType): CellType {
        if (c == CellType.SUN) return CellType.MOON
        if (c == CellType.MOON) return CellType.SUN
        return CellType.EMPTY
    }
    
    fun setPendingHint(info: PendingHintInfo) {
        activeHint = info
        hintExplanation = info.explanation
        targetCell = Pair(info.row, info.col)
        sourceCells = info.sources
    }

    fun applyActiveHint() {
        val hint = activeHint ?: return
        saveHistory()
        val newGrid = grid.map { it.toMutableList() }
        newGrid[hint.row][hint.col] = hint.change
        grid = newGrid
        clearHint()
        hintCount++
        checkWin()
    }
    
    fun hint() {
        if (isWon) return
        clearHint()
        
        // 0. PRIORITIZE CHECKING USER MISTAKES FIRST
        val userMistakes = mutableListOf<PendingHintInfo>()
        for (r in 0 until 6) {
            for (c in 0 until 6) {
                if (grid[r][c] != CellType.EMPTY && currentLevel.initial[r][c] == CellType.EMPTY) {
                    if (grid[r][c] != currentLevel.solution[r][c]) {
                        val wrongVal = grid[r][c]
                        val correctVal = currentLevel.solution[r][c]
                        val wrongEmoji = if (wrongVal == CellType.SUN) "🟡" else "🌙"
                        val correctEmoji = if (correctVal == CellType.SUN) "🟡" else "🌙"
                        
                        var explained = false
                        
                        // Check A: Is it violating a vertical constraint?
                        for (vc in currentLevel.vConstraints) {
                            if (vc.row == r && (vc.col == c || vc.col + 1 == c)) {
                                val otherC = if (vc.col == c) c + 1 else c - 1
                                val otherVal = grid[r][otherC]
                                if (otherVal != CellType.EMPTY) {
                                    val isBroken = when (vc.type) {
                                        ConstraintType.EQUALS -> wrongVal != otherVal
                                        ConstraintType.CROSS -> wrongVal == otherVal
                                    }
                                    if (isBroken) {
                                        val otherEmoji = if (otherVal == CellType.SUN) "🟡" else "🌙"
                                        val symbol = if (vc.type == ConstraintType.EQUALS) "=" else "×"
                                        val requirementText = if (vc.type == ConstraintType.EQUALS) "equal to" else "different from"
                                        val explanation = "Constraint Mistake detected!\n\n" +
                                            "You placed $wrongEmoji at Row ${r+1}, Column ${c+1}, which is connected by '$symbol' to the $otherEmoji at Column ${otherC+1}.\n\n" +
                                            "This breaks the rule that they must be $requirementText each other! Therefore, this cell must be corrected to $correctEmoji."
                                        
                                        userMistakes.add(PendingHintInfo(r, c, correctVal, setOf(Pair(r, otherC)), explanation))
                                        explained = true
                                        break
                                    }
                                }
                            }
                        }
                        if (explained) continue
                        
                        // Check B: Is it violating a horizontal constraint?
                        for (hc in currentLevel.hConstraints) {
                            if (hc.col == c && (hc.row == r || hc.row + 1 == r)) {
                                val otherR = if (hc.row == r) r + 1 else r - 1
                                val otherVal = grid[otherR][c]
                                if (otherVal != CellType.EMPTY) {
                                    val isBroken = when (hc.type) {
                                        ConstraintType.EQUALS -> wrongVal != otherVal
                                        ConstraintType.CROSS -> wrongVal == otherVal
                                    }
                                    if (isBroken) {
                                        val otherEmoji = if (otherVal == CellType.SUN) "🟡" else "🌙"
                                        val symbol = if (hc.type == ConstraintType.EQUALS) "=" else "×"
                                        val requirementText = if (hc.type == ConstraintType.EQUALS) "equal to" else "different from"
                                        val explanation = "Constraint Mistake detected!\n\n" +
                                            "You placed $wrongEmoji at Row ${r+1}, Column ${c+1}, which is connected by '$symbol' to the $otherEmoji at Row ${otherR+1}.\n\n" +
                                            "This breaks the rule that they must be $requirementText each other! Therefore, this cell must be corrected to $correctEmoji."
                                        
                                        userMistakes.add(PendingHintInfo(r, c, correctVal, setOf(Pair(otherR, c)), explanation))
                                        explained = true
                                        break
                                    }
                                }
                            }
                        }
                        if (explained) continue
                        
                        // Check C: Does it create 3 consecutive identical symbols horizontally?
                        var tripleFound = false
                        // Case 1: [this, c+1, c+2]
                        if (c <= 3 && grid[r][c+1] == wrongVal && grid[r][c+2] == wrongVal) {
                            tripleFound = true
                            val explanation = "Triple Consecutive Mistake!\n\n" +
                                "Placing $wrongEmoji at Row ${r+1}, Column ${c+1} creates three consecutive identical symbols with Columns ${c+2} and ${c+3}.\n\n" +
                                "Since no three identical symbols can be adjacent, this cell must be corrected to $correctEmoji."
                            userMistakes.add(PendingHintInfo(r, c, correctVal, setOf(Pair(r, c+1), Pair(r, c+2)), explanation))
                        }
                        // Case 2: [c-1, this, c+1]
                        else if (c >= 1 && c <= 4 && grid[r][c-1] == wrongVal && grid[r][c+1] == wrongVal) {
                            tripleFound = true
                            val explanation = "Triple Consecutive Mistake!\n\n" +
                                "Placing $wrongEmoji at Row ${r+1}, Column ${c+1} creates three consecutive identical symbols with Columns ${c} and ${c+2}.\n\n" +
                                "Since no three identical symbols can be adjacent, this cell must be corrected to $correctEmoji."
                            userMistakes.add(PendingHintInfo(r, c, correctVal, setOf(Pair(r, c-1), Pair(r, c+1)), explanation))
                        }
                        // Case 3: [c-2, c-1, this]
                        else if (c >= 2 && grid[r][c-2] == wrongVal && grid[r][c-1] == wrongVal) {
                            tripleFound = true
                            val explanation = "Triple Consecutive Mistake!\n\n" +
                                "Placing $wrongEmoji at Row ${r+1}, Column ${c+1} creates three consecutive identical symbols with Columns ${c-1} and ${c}.\n\n" +
                                "Since no three identical symbols can be adjacent, this cell must be corrected to $correctEmoji."
                            userMistakes.add(PendingHintInfo(r, c, correctVal, setOf(Pair(r, c-2), Pair(r, c-1)), explanation))
                        }
                        if (tripleFound) continue
                        
                        // Check D: Does it create 3 consecutive identical symbols vertically?
                        // Case 1: [this, r+1, r+2]
                        if (r <= 3 && grid[r+1][c] == wrongVal && grid[r+2][c] == wrongVal) {
                            tripleFound = true
                            val explanation = "Triple Consecutive Mistake!\n\n" +
                                "Placing $wrongEmoji at Row ${r+1}, Column ${c+1} creates three consecutive identical symbols with Rows ${r+2} and ${r+3}.\n\n" +
                                "Since no three identical symbols can be adjacent, this cell must be corrected to $correctEmoji."
                            userMistakes.add(PendingHintInfo(r, c, correctVal, setOf(Pair(r+1, c), Pair(r+2, c)), explanation))
                        }
                        // Case 2: [r-1, this, r+1]
                        else if (r >= 1 && r <= 4 && grid[r-1][c] == wrongVal && grid[r+1][c] == wrongVal) {
                            tripleFound = true
                            val explanation = "Triple Consecutive Mistake!\n\n" +
                                "Placing $wrongEmoji at Row ${r+1}, Column ${c+1} creates three consecutive identical symbols with Rows ${r} and ${r+2}.\n\n" +
                                "Since no three identical symbols can be adjacent, this cell must be corrected to $correctEmoji."
                            userMistakes.add(PendingHintInfo(r, c, correctVal, setOf(Pair(r-1, c), Pair(r+1, c)), explanation))
                        }
                        // Case 3: [r-2, r-1, this]
                        else if (r >= 2 && grid[r-2][c] == wrongVal && grid[r-1][c] == wrongVal) {
                            tripleFound = true
                            val explanation = "Triple Consecutive Mistake!\n\n" +
                                "Placing $wrongEmoji at Row ${r+1}, Column ${c+1} creates three consecutive identical symbols with Rows ${r-1} and ${r}.\n\n" +
                                "Since no three identical symbols can be adjacent, this cell must be corrected to $correctEmoji."
                            userMistakes.add(PendingHintInfo(r, c, correctVal, setOf(Pair(r-2, c), Pair(r-1, c)), explanation))
                        }
                        if (tripleFound) continue
                        
                        // Check E: Row balance exceeded
                        val rowCount = grid[r].count { it == wrongVal }
                        if (rowCount > 3) {
                            val sources = grid[r].mapIndexedNotNull { i, type -> if (type == wrongVal && i != c) Pair(r, i) else null }.toSet()
                            val countLabel = if (wrongVal == CellType.SUN) "Suns (🟡)" else "Moons (🌙)"
                            val explanation = "Row Balance Mistake!\n\n" +
                                "You placed $wrongEmoji at Row ${r+1}, Column ${c+1}, but this row already has $rowCount $countLabel.\n\n" +
                                "Since each row must have exactly 3 Suns and 3 Moons, this cell must be corrected to $correctEmoji."
                            userMistakes.add(PendingHintInfo(r, c, correctVal, sources, explanation))
                            continue
                        }
                        
                        // Check F: Col balance exceeded
                        val colCount = (0 until 6).count { grid[it][c] == wrongVal }
                        if (colCount > 3) {
                            val sources = (0 until 6).mapNotNull { if (grid[it][c] == wrongVal && it != r) Pair(it, c) else null }.toSet()
                            val countLabel = if (wrongVal == CellType.SUN) "Suns (🟡)" else "Moons (🌙)"
                            val explanation = "Column Balance Mistake!\n\n" +
                                "You placed $wrongEmoji at Row ${r+1}, Column ${c+1}, but this column already has $colCount $countLabel.\n\n" +
                                "Since each column must have exactly 3 Suns and 3 Moons, this cell must be corrected to $correctEmoji."
                            userMistakes.add(PendingHintInfo(r, c, correctVal, sources, explanation))
                            continue
                        }
                        
                        // Check G: Indirect mistake (requires looking ahead / trace contradiction)
                        val traceResult = traceContradictionChain(r, c, wrongVal)
                        if (traceResult != null) {
                            val (steps, contradiction) = traceResult
                            val explanation = formatContradictionTraceForMove(r, c, wrongVal, steps, contradiction, correctVal)
                            userMistakes.add(PendingHintInfo(r, c, correctVal, emptySet(), explanation))
                        } else {
                            val explanation = "Logic Mistake Detected!\n\n" +
                                "Your placed symbol $wrongEmoji at Row ${r+1}, Column ${c+1} is incorrect and leads to an eventual contradiction.\n\n" +
                                "Therefore, this cell must be corrected to $correctEmoji."
                            userMistakes.add(PendingHintInfo(r, c, correctVal, emptySet(), explanation))
                        }
                    }
                }
            }
        }
        
        if (userMistakes.isNotEmpty()) {
            val chosenMistake = userMistakes.firstOrNull { it.sources.isNotEmpty() } ?: userMistakes.first()
            setPendingHint(chosenMistake)
            return
        }

        // Collect candidates for visual Sandwich & Pair Rules (easiest for humans to spot)
        val simpleCandidates = mutableListOf<PendingHintInfo>()
        
        // 1. Sandwich Rule (Row)
        for (r in 0 until 6) {
            for (c in 0 until 4) {
                if (grid[r][c] != CellType.EMPTY && grid[r][c] == grid[r][c+2] && grid[r][c+1] == CellType.EMPTY) {
                    val solVal = currentLevel.solution[r][c+1]
                    val emoji = if (solVal == CellType.SUN) "🟡" else "🌙"
                    simpleCandidates.add(
                        PendingHintInfo(
                            r, c+1, solVal,
                            setOf(Pair(r, c), Pair(r, c+2)),
                            "No more than 2 🟡 or 🌙 may be next to each other, either vertically or horizontally.\n\nTherefore the highlighted cell must be a $emoji."
                        )
                    )
                }
            }
        }
        
        // 1. Sandwich Rule (Col)
        for (c in 0 until 6) {
            for (r in 0 until 4) {
                if (grid[r][c] != CellType.EMPTY && grid[r][c] == grid[r+2][c] && grid[r+1][c] == CellType.EMPTY) {
                    val solVal = currentLevel.solution[r+1][c]
                    val emoji = if (solVal == CellType.SUN) "🟡" else "🌙"
                    simpleCandidates.add(
                        PendingHintInfo(
                            r+1, c, solVal,
                            setOf(Pair(r, c), Pair(r+2, c)),
                            "No more than 2 🟡 or 🌙 may be next to each other, either vertically or horizontally.\n\nTherefore the highlighted cell must be a $emoji."
                        )
                    )
                }
            }
        }

        // 2. Pair Rule (Row)
        for (r in 0 until 6) {
            for (c in 0 until 5) {
                if (grid[r][c] != CellType.EMPTY && grid[r][c] == grid[r][c+1]) {
                    if (c > 0 && grid[r][c-1] == CellType.EMPTY) {
                        val solVal = currentLevel.solution[r][c-1]
                        val emoji = if (solVal == CellType.SUN) "🟡" else "🌙"
                        simpleCandidates.add(
                            PendingHintInfo(
                                r, c-1, solVal,
                                setOf(Pair(r, c), Pair(r, c+1)),
                                "No more than 2 🟡 or 🌙 may be next to each other, either vertically or horizontally.\n\nTherefore the highlighted cell must be a $emoji."
                            )
                        )
                    }
                    if (c < 4 && grid[r][c+2] == CellType.EMPTY) {
                        val solVal = currentLevel.solution[r][c+2]
                        val emoji = if (solVal == CellType.SUN) "🟡" else "🌙"
                        simpleCandidates.add(
                            PendingHintInfo(
                                r, c+2, solVal,
                                setOf(Pair(r, c), Pair(r, c+1)),
                                "No more than 2 🟡 or 🌙 may be next to each other, either vertically or horizontally.\n\nTherefore the highlighted cell must be a $emoji."
                            )
                        )
                    }
                }
            }
        }
        
        // 2. Pair Rule (Col)
        for (c in 0 until 6) {
            for (r in 0 until 5) {
                if (grid[r][c] != CellType.EMPTY && grid[r][c] == grid[r+1][c]) {
                    if (r > 0 && grid[r-1][c] == CellType.EMPTY) {
                        val solVal = currentLevel.solution[r-1][c]
                        val emoji = if (solVal == CellType.SUN) "🟡" else "🌙"
                        simpleCandidates.add(
                            PendingHintInfo(
                                r-1, c, solVal,
                                setOf(Pair(r, c), Pair(r+1, c)),
                                "No more than 2 🟡 or 🌙 may be next to each other, either vertically or horizontally.\n\nTherefore the highlighted cell must be a $emoji."
                            )
                        )
                    }
                    if (r < 4 && grid[r+2][c] == CellType.EMPTY) {
                        val solVal = currentLevel.solution[r+2][c]
                        val emoji = if (solVal == CellType.SUN) "🟡" else "🌙"
                        simpleCandidates.add(
                            PendingHintInfo(
                                r+2, c, solVal,
                                setOf(Pair(r, c), Pair(r+1, c)),
                                "No more than 2 🟡 or 🌙 may be next to each other, either vertically or horizontally.\n\nTherefore the highlighted cell must be a $emoji."
                            )
                        )
                    }
                }
            }
        }

        if (simpleCandidates.isNotEmpty()) {
            setPendingHint(simpleCandidates.random())
            return
        }

        // 3. Relation Rules (vConstraints & hConstraints)
        val relationCandidates = mutableListOf<PendingHintInfo>()
        for (vc in currentLevel.vConstraints) {
            val r = vc.row
            val c = vc.col
            val left = grid[r][c]
            val right = grid[r][c+1]
            if (left != CellType.EMPTY && right == CellType.EMPTY) {
                val solVal = currentLevel.solution[r][c+1]
                val emoji = if (solVal == CellType.SUN) "🟡" else "🌙"
                val symbol = if (vc.type == ConstraintType.EQUALS) "=" else "×"
                val logicText = if (vc.type == ConstraintType.EQUALS) "equal" else "different"
                relationCandidates.add(
                    PendingHintInfo(
                        r, c+1, solVal,
                        setOf(Pair(r, vc.col)),
                        "Relation Rule: The cells are connected by '$symbol', so they must be $logicText.\n\nTherefore the highlighted cell must be a $emoji."
                    )
                )
            }
            if (left == CellType.EMPTY && right != CellType.EMPTY) {
                val solVal = currentLevel.solution[r][c]
                val emoji = if (solVal == CellType.SUN) "🟡" else "🌙"
                val symbol = if (vc.type == ConstraintType.EQUALS) "=" else "×"
                val logicText = if (vc.type == ConstraintType.EQUALS) "equal" else "different"
                relationCandidates.add(
                    PendingHintInfo(
                        r, c, solVal,
                        setOf(Pair(r, vc.col + 1)),
                        "Relation Rule: The cells are connected by '$symbol', so they must be $logicText.\n\nTherefore the highlighted cell must be a $emoji."
                    )
                )
            }
        }
        for (hc in currentLevel.hConstraints) {
            val r = hc.row
            val c = hc.col
            val top = grid[r][c]
            val bottom = grid[r+1][c]
            if (top != CellType.EMPTY && bottom == CellType.EMPTY) {
                val solVal = currentLevel.solution[r+1][c]
                val emoji = if (solVal == CellType.SUN) "🟡" else "🌙"
                val symbol = if (hc.type == ConstraintType.EQUALS) "=" else "×"
                val logicText = if (hc.type == ConstraintType.EQUALS) "equal" else "different"
                relationCandidates.add(
                    PendingHintInfo(
                        r+1, c, solVal,
                        setOf(Pair(hc.row, c)),
                        "Relation Rule: The cells are connected by '$symbol', so they must be $logicText.\n\nTherefore the highlighted cell must be a $emoji."
                    )
                )
            }
            if (top == CellType.EMPTY && bottom != CellType.EMPTY) {
                val solVal = currentLevel.solution[r][c]
                val emoji = if (solVal == CellType.SUN) "🟡" else "🌙"
                val symbol = if (hc.type == ConstraintType.EQUALS) "=" else "×"
                val logicText = if (hc.type == ConstraintType.EQUALS) "equal" else "different"
                relationCandidates.add(
                    PendingHintInfo(
                        r, c, solVal,
                        setOf(Pair(hc.row + 1, c)),
                        "Relation Rule: The cells are connected by '$symbol', so they must be $logicText.\n\nTherefore the highlighted cell must be a $emoji."
                    )
                )
            }
        }

        if (relationCandidates.isNotEmpty()) {
            setPendingHint(relationCandidates.random())
            return
        }

        // 4. Balance Rule (Row & Col)
        val balanceCandidates = mutableListOf<PendingHintInfo>()
        for (r in 0 until 6) {
            val suns = grid[r].count { it == CellType.SUN }
            val moons = grid[r].count { it == CellType.MOON }
            if (suns == 3 && moons < 3) {
                val emptyCols = grid[r].mapIndexedNotNull { i, cellType -> if (cellType == CellType.EMPTY) i else null }
                if (emptyCols.isNotEmpty()) {
                    val c = emptyCols.random()
                    val solVal = currentLevel.solution[r][c]
                    val emoji = if (solVal == CellType.SUN) "🟡" else "🌙"
                    val sources = grid[r].mapIndexedNotNull { i, cellType -> if (cellType == CellType.SUN) Pair(r, i) else null }.toSet()
                    balanceCandidates.add(
                        PendingHintInfo(
                            r, c, solVal, sources,
                            "Each row and column must contain an equal number of 🟡 and 🌙 (3 of each).\n\nTherefore the highlighted cell must be a $emoji."
                        )
                    )
                }
            }
            if (moons == 3 && suns < 3) {
                val emptyCols = grid[r].mapIndexedNotNull { i, cellType -> if (cellType == CellType.EMPTY) i else null }
                if (emptyCols.isNotEmpty()) {
                    val c = emptyCols.random()
                    val solVal = currentLevel.solution[r][c]
                    val emoji = if (solVal == CellType.SUN) "🟡" else "🌙"
                    val sources = grid[r].mapIndexedNotNull { i, cellType -> if (cellType == CellType.MOON) Pair(r, i) else null }.toSet()
                    balanceCandidates.add(
                        PendingHintInfo(
                            r, c, solVal, sources,
                            "Each row and column must contain an equal number of 🟡 and 🌙 (3 of each).\n\nTherefore the highlighted cell must be a $emoji."
                        )
                    )
                }
            }
        }
        
        for (c in 0 until 6) {
            val suns = (0 until 6).count { grid[it][c] == CellType.SUN }
            val moons = (0 until 6).count { grid[it][c] == CellType.MOON }
            if (suns == 3 && moons < 3) {
                val emptyRows = (0 until 6).filter { grid[it][c] == CellType.EMPTY }
                if (emptyRows.isNotEmpty()) {
                    val r = emptyRows.random()
                    val solVal = currentLevel.solution[r][c]
                    val emoji = if (solVal == CellType.SUN) "🟡" else "🌙"
                    val sources = (0 until 6).mapNotNull { if (grid[it][c] == CellType.SUN) Pair(it, c) else null }.toSet()
                    balanceCandidates.add(
                        PendingHintInfo(
                            r, c, solVal, sources,
                            "Each row and column must contain an equal number of 🟡 and 🌙 (3 of each).\n\nTherefore the highlighted cell must be a $emoji."
                        )
                    )
                }
            }
            if (moons == 3 && suns < 3) {
                val emptyRows = (0 until 6).filter { grid[it][c] == CellType.EMPTY }
                if (emptyRows.isNotEmpty()) {
                    val r = emptyRows.random()
                    val solVal = currentLevel.solution[r][c]
                    val emoji = if (solVal == CellType.SUN) "🟡" else "🌙"
                    val sources = (0 until 6).mapNotNull { if (grid[it][c] == CellType.MOON) Pair(it, c) else null }.toSet()
                    balanceCandidates.add(
                        PendingHintInfo(
                            r, c, solVal, sources,
                            "Each row and column must contain an equal number of 🟡 and 🌙 (3 of each).\n\nTherefore the highlighted cell must be a $emoji."
                        )
                    )
                }
            }
        }

        if (balanceCandidates.isNotEmpty()) {
            setPendingHint(balanceCandidates.random())
            return
        }

        // 5. Lookahead deduction tracing
        for (r in 0 until 6) {
            for (c in 0 until 6) {
                if (grid[r][c] == CellType.EMPTY) {
                    val solVal = currentLevel.solution[r][c]
                    val oppositeVal = getOpposite(solVal)
                    val traceResult = traceContradictionChain(r, c, oppositeVal)
                    if (traceResult != null) {
                        val (steps, contradiction) = traceResult
                        val explanation = formatContradictionTrace(r, c, oppositeVal, steps, contradiction, solVal)
                        setPendingHint(
                            PendingHintInfo(
                                r, c, solVal, emptySet(),
                                explanation
                            )
                        )
                        return
                    }
                }
            }
        }

        // Final candidate traceback if nothing triggered:
        for (r in 0 until 6) {
            for (c in 0 until 6) {
                if (grid[r][c] == CellType.EMPTY) {
                    val solVal = currentLevel.solution[r][c]
                    val emoji = if (solVal == CellType.SUN) "🟡" else "🌙"
                    setPendingHint(
                        PendingHintInfo(
                            r, c, solVal, emptySet(),
                            "Deduction check: Since other rules are exhausted, this cell at Row ${r+1}, Col ${c+1} must be $emoji to complete the grid puzzle logically."
                        )
                    )
                    return
                }
            }
        }
    }

    private fun traceContradictionChain(startR: Int, startC: Int, assumedType: CellType): Pair<List<TraceStep>, String>? {
        val g = Array(6) { r -> Array(6) { c -> grid[r][c] } }
        g[startR][startC] = assumedType
        
        val knownCells = mutableSetOf<Pair<Int, Int>>()
        for (r in 0 until 6) {
            for (c in 0 until 6) {
                if (grid[r][c] != CellType.EMPTY) {
                    knownCells.add(Pair(r, c))
                }
            }
        }
        knownCells.add(Pair(startR, startC))
        
        val steps = mutableListOf<TraceStep>()
        fun emojiOf(type: CellType) = if (type == CellType.SUN) "🟡" else "🌙"
        
        fun findContradiction(): String? {
            // 1. Triple consecutive in Row
            for (r in 0 until 6) {
                for (c in 0 until 4) {
                    if (g[r][c] != CellType.EMPTY && g[r][c] == g[r][c+1] && g[r][c] == g[r][c+2]) {
                        return "Row ${r+1} would have three consecutive identical symbols (${emojiOf(g[r][c])}) at Columns ${c+1}, ${c+2}, and ${c+3}."
                    }
                }
            }
            // 2. Triple consecutive in Col
            for (c in 0 until 6) {
                for (r in 0 until 4) {
                    if (g[r][c] != CellType.EMPTY && g[r][c] == g[r+1][c] && g[r][c] == g[r+2][c]) {
                        return "Column ${c+1} would have three consecutive identical symbols (${emojiOf(g[r][c])}) at Rows ${r+1}, ${r+2}, and ${r+3}."
                    }
                }
            }
            // 3. Count in Row
            for (r in 0 until 6) {
                val s = g[r].count { it == CellType.SUN }
                val m = g[r].count { it == CellType.MOON }
                if (s > 3) return "Row ${r+1} would contain $s Suns (🟡), which exceeds the limit of exactly 3."
                if (m > 3) return "Row ${r+1} would contain $m Moons (🌙), which exceeds the limit of exactly 3."
            }
            // 4. Count in Col
            for (c in 0 until 6) {
                val s = (0 until 6).count { g[it][c] == CellType.SUN }
                val m = (0 until 6).count { g[it][c] == CellType.MOON }
                if (s > 3) return "Column ${c+1} would contain $s Suns (🟡), which exceeds the limit of exactly 3."
                if (m > 3) return "Column ${c+1} would contain $m Moons (🌙), which exceeds the limit of exactly 3."
            }
            // 5. Relations (vConstraints)
            for (vc in currentLevel.vConstraints) {
                val left = g[vc.row][vc.col]
                val right = g[vc.row][vc.col+1]
                if (left != CellType.EMPTY && right != CellType.EMPTY) {
                    if (vc.type == ConstraintType.EQUALS && left != right) {
                        return "The '=' relationship at Row ${vc.row+1} between Column ${vc.col+1} (${emojiOf(left)}) and Column ${vc.col+2} (${emojiOf(right)}) is broken."
                    }
                    if (vc.type == ConstraintType.CROSS && left == right) {
                        return "The '×' relationship at Row ${vc.row+1} between Column ${vc.col+1} (${emojiOf(left)}) and Column ${vc.col+2} (${emojiOf(right)}) is broken."
                    }
                }
            }
            // 6. Relations (hConstraints)
            for (hc in currentLevel.hConstraints) {
                val top = g[hc.row][hc.col]
                val bottom = g[hc.row+1][hc.col]
                if (top != CellType.EMPTY && bottom != CellType.EMPTY) {
                    if (hc.type == ConstraintType.EQUALS && top != bottom) {
                        return "The '=' relationship at Column ${hc.col+1} between Row ${hc.row+1} (${emojiOf(top)}) and Row ${hc.row+2} (${emojiOf(bottom)}) is broken."
                    }
                    if (hc.type == ConstraintType.CROSS && top == bottom) {
                        return "The '×' relationship at Column ${hc.col+1} between Row ${hc.row+1} (${emojiOf(top)}) and Row ${hc.row+2} (${emojiOf(bottom)}) is broken."
                    }
                }
            }
            return null
        }
        
        val initialContra = findContradiction()
        if (initialContra != null) {
            return Pair(steps, initialContra)
        }
        
        var changed = true
        var iteration = 0
        while (changed && iteration < 36) {
            changed = false
            iteration++
            
            // --- 1. Sandwich Rows ---
            for (r in 0 until 6) {
                for (c in 0 until 4) {
                    if (g[r][c] != CellType.EMPTY && g[r][c] == g[r][c+2] && g[r][c+1] == CellType.EMPTY) {
                        if (Pair(r, c) in knownCells && Pair(r, c+2) in knownCells) {
                            val derived = getOpposite(g[r][c])
                            g[r][c+1] = derived
                            knownCells.add(Pair(r, c+1))
                            steps.add(
                                TraceStep(
                                    r, c+1, derived,
                                    "Sandwich Rule: Row ${r+1}, Columns ${c+1} and ${c+3} contain identical symbols, forcing the center cell to be ${emojiOf(derived)}."
                                )
                            )
                            changed = true
                            break
                        }
                    }
                }
                if (changed) break
            }
            if (changed) {
                val contra = findContradiction()
                if (contra != null) return Pair(steps, contra)
                continue
            }
            
            // --- 2. Sandwich Cols ---
            for (c in 0 until 6) {
                for (r in 0 until 4) {
                    if (g[r][c] != CellType.EMPTY && g[r][c] == g[r+2][c] && g[r+1][c] == CellType.EMPTY) {
                        if (Pair(r, c) in knownCells && Pair(r+2, c) in knownCells) {
                            val derived = getOpposite(g[r][c])
                            g[r+1][c] = derived
                            knownCells.add(Pair(r+1, c))
                            steps.add(
                                TraceStep(
                                    r+1, c, derived,
                                    "Sandwich Rule: Column ${c+1}, Rows ${r+1} and ${r+3} contain identical symbols, forcing the center cell to be ${emojiOf(derived)}."
                                )
                            )
                            changed = true
                            break
                        }
                    }
                }
                if (changed) break
            }
            if (changed) {
                val contra = findContradiction()
                if (contra != null) return Pair(steps, contra)
                continue
            }
            
            // --- 3. Pair Rows ---
            for (r in 0 until 6) {
                for (c in 0 until 5) {
                    if (g[r][c] != CellType.EMPTY && g[r][c] == g[r][c+1]) {
                        if (Pair(r, c) in knownCells && Pair(r, c+1) in knownCells) {
                            if (c > 0 && g[r][c-1] == CellType.EMPTY) {
                                val derived = getOpposite(g[r][c])
                                g[r][c-1] = derived
                                knownCells.add(Pair(r, c-1))
                                steps.add(
                                    TraceStep(
                                        r, c-1, derived,
                                        "Pair Rule: Row ${r+1}, Columns ${c+1} and ${c+2} contain adjacent identical symbols, forcing the preceding cell to be ${emojiOf(derived)}."
                                    )
                                )
                                changed = true
                                break
                            }
                            if (c < 4 && g[r][c+2] == CellType.EMPTY) {
                                val derived = getOpposite(g[r][c])
                                g[r][c+2] = derived
                                knownCells.add(Pair(r, c+2))
                                steps.add(
                                    TraceStep(
                                        r, c+2, derived,
                                        "Pair Rule: Row ${r+1}, Columns ${c+1} and ${c+2} contain adjacent identical symbols, forcing the succeeding cell to be ${emojiOf(derived)}."
                                    )
                                )
                                changed = true
                                break
                            }
                        }
                    }
                }
                if (changed) break
            }
            if (changed) {
                val contra = findContradiction()
                if (contra != null) return Pair(steps, contra)
                continue
            }
            
            // --- 4. Pair Cols ---
            for (c in 0 until 6) {
                for (r in 0 until 5) {
                    if (g[r][c] != CellType.EMPTY && g[r][c] == g[r+1][c]) {
                        if (Pair(r, c) in knownCells && Pair(r+1, c) in knownCells) {
                            if (r > 0 && g[r-1][c] == CellType.EMPTY) {
                                val derived = getOpposite(g[r][c])
                                g[r-1][c] = derived
                                knownCells.add(Pair(r-1, c))
                                steps.add(
                                    TraceStep(
                                        r-1, c, derived,
                                        "Pair Rule: Column ${c+1}, Rows ${r+1} and ${r+2} contain adjacent identical symbols, forcing the upper cell to be ${emojiOf(derived)}."
                                    )
                                )
                                changed = true
                                break
                            }
                            if (r < 4 && g[r+2][c] == CellType.EMPTY) {
                                val derived = getOpposite(g[r][c])
                                g[r+2][c] = derived
                                knownCells.add(Pair(r+2, c))
                                steps.add(
                                    TraceStep(
                                        r+2, c, derived,
                                        "Pair Rule: Column ${c+1}, Rows ${r+1} and ${r+2} contain adjacent identical symbols, forcing the lower cell to be ${emojiOf(derived)}."
                                    )
                                )
                                changed = true
                                break
                            }
                        }
                    }
                }
                if (changed) break
            }
            if (changed) {
                val contra = findContradiction()
                if (contra != null) return Pair(steps, contra)
                continue
            }
            
            // --- 5. Relations (vConstraints) ---
            for (vc in currentLevel.vConstraints) {
                val r = vc.row
                val c = vc.col
                val symbol = if (vc.type == ConstraintType.EQUALS) "=" else "×"
                if (g[r][c] != CellType.EMPTY && g[r][c+1] == CellType.EMPTY && Pair(r, c) in knownCells) {
                    val derived = if (vc.type == ConstraintType.EQUALS) g[r][c] else getOpposite(g[r][c])
                    g[r][c+1] = derived
                    knownCells.add(Pair(r, c+1))
                    steps.add(
                        TraceStep(
                            r, c+1, derived,
                            "Constraint Rule: Row ${r+1}, Column ${c+1} and Column ${2+c} are connected by '$symbol', forcing Column ${c+2} to be ${emojiOf(derived)}."
                        )
                    )
                    changed = true
                    break
                }
                if (g[r][c] == CellType.EMPTY && g[r][c+1] != CellType.EMPTY && Pair(r, c+1) in knownCells) {
                    val derived = if (vc.type == ConstraintType.EQUALS) g[r][c+1] else getOpposite(g[r][c+1])
                    g[r][c] = derived
                    knownCells.add(Pair(r, c))
                    steps.add(
                        TraceStep(
                            r, c, derived,
                            "Constraint Rule: Row ${r+1}, Column ${c+1} and Column ${2+c} are connected by '$symbol', forcing Column ${c+1} to be ${emojiOf(derived)}."
                        )
                    )
                    changed = true
                    break
                }
            }
            if (changed) {
                val contra = findContradiction()
                if (contra != null) return Pair(steps, contra)
                continue
            }
            
            // --- 6. Relations (hConstraints) ---
            for (hc in currentLevel.hConstraints) {
                val r = hc.row
                val c = hc.col
                val symbol = if (hc.type == ConstraintType.EQUALS) "=" else "×"
                if (g[r][c] != CellType.EMPTY && g[r+1][c] == CellType.EMPTY && Pair(r, c) in knownCells) {
                    val derived = if (hc.type == ConstraintType.EQUALS) g[r][c] else getOpposite(g[r][c])
                    g[r+1][c] = derived
                    knownCells.add(Pair(r+1, c))
                    steps.add(
                        TraceStep(
                            r+1, c, derived,
                            "Constraint Rule: Row ${r+1} and Row ${r+2} are connected by '$symbol' in Column ${c+1}, forcing Row ${r+2} Column ${c+1} to be ${emojiOf(derived)}."
                        )
                    )
                    changed = true
                    break
                }
                if (g[r][c] == CellType.EMPTY && g[r+1][c] != CellType.EMPTY && Pair(r+1, c) in knownCells) {
                    val derived = if (hc.type == ConstraintType.EQUALS) g[r+1][c] else getOpposite(g[r+1][c])
                    g[r][c] = derived
                    knownCells.add(Pair(r, c))
                    steps.add(
                        TraceStep(
                            r, c, derived,
                            "Constraint Rule: Row ${r+1} and Row ${r+2} are connected by '$symbol' in Column ${c+1}, forcing Row ${r+1} Column ${c+1} to be ${emojiOf(derived)}."
                        )
                    )
                    changed = true
                    break
                }
            }
            if (changed) {
                val contra = findContradiction()
                if (contra != null) return Pair(steps, contra)
                continue
            }
            
            // --- 7. Balance Rule (Rows) ---
            for (r in 0 until 6) {
                val sunCols = (0 until 6).filter { Pair(r, it) in knownCells && g[r][it] == CellType.SUN }
                val moonCols = (0 until 6).filter { Pair(r, it) in knownCells && g[r][it] == CellType.MOON }
                
                if (sunCols.size == 3) {
                    val emptyCols = (0 until 6).filter { g[r][it] == CellType.EMPTY }
                    if (emptyCols.isNotEmpty()) {
                        val firstEmpty = emptyCols.first()
                        g[r][firstEmpty] = CellType.MOON
                        knownCells.add(Pair(r, firstEmpty))
                        steps.add(
                            TraceStep(
                                r, firstEmpty, CellType.MOON,
                                "Balance Rule: Row ${r+1} already contains 3 Suns, forcing remaining empty cells including Column ${firstEmpty+1} to be 🌙."
                            )
                        )
                        changed = true
                        break
                    }
                }
                if (moonCols.size == 3) {
                    val emptyCols = (0 until 6).filter { g[r][it] == CellType.EMPTY }
                    if (emptyCols.isNotEmpty()) {
                        val firstEmpty = emptyCols.first()
                        g[r][firstEmpty] = CellType.SUN
                        knownCells.add(Pair(r, firstEmpty))
                        steps.add(
                            TraceStep(
                                r, firstEmpty, CellType.SUN,
                                "Balance Rule: Row ${r+1} already contains 3 Moons, forcing remaining empty cells including Column ${firstEmpty+1} to be 🟡."
                            )
                        )
                        changed = true
                        break
                    }
                }
            }
            if (changed) {
                val contra = findContradiction()
                if (contra != null) return Pair(steps, contra)
                continue
            }
            
            // --- 8. Balance Rule (Cols) ---
            for (c in 0 until 6) {
                val sunRows = (0 until 6).filter { Pair(it, c) in knownCells && g[it][c] == CellType.SUN }
                val moonRows = (0 until 6).filter { Pair(it, c) in knownCells && g[it][c] == CellType.MOON }
                
                if (sunRows.size == 3) {
                    val emptyRows = (0 until 6).filter { g[it][c] == CellType.EMPTY }
                    if (emptyRows.isNotEmpty()) {
                        val firstEmpty = emptyRows.first()
                        g[firstEmpty][c] = CellType.MOON
                        knownCells.add(Pair(firstEmpty, c))
                        steps.add(
                            TraceStep(
                                firstEmpty, c, CellType.MOON,
                                "Balance Rule: Column ${c+1} already contains 3 Suns, forcing remaining empty cells including Row ${firstEmpty+1} to be 🌙."
                            )
                        )
                        changed = true
                        break
                    }
                }
                if (moonRows.size == 3) {
                    val emptyRows = (0 until 6).filter { g[it][c] == CellType.EMPTY }
                    if (emptyRows.isNotEmpty()) {
                        val firstEmpty = emptyRows.first()
                        g[firstEmpty][c] = CellType.SUN
                        knownCells.add(Pair(firstEmpty, c))
                        steps.add(
                            TraceStep(
                                firstEmpty, c, CellType.SUN,
                                "Balance Rule: Column ${c+1} already contains 3 Moons, forcing remaining empty cells including Row ${firstEmpty+1} to be 🟡."
                            )
                        )
                        changed = true
                        break
                    }
                }
            }
            if (changed) {
                val contra = findContradiction()
                if (contra != null) return Pair(steps, contra)
                continue
            }
        }
        
        return null
    }

    private fun formatContradictionTrace(
        startR: Int,
        startC: Int,
        assumedVal: CellType,
        steps: List<TraceStep>,
        contradiction: String,
        correctVal: CellType
    ): String {
        val startEmoji = if (assumedVal == CellType.SUN) "🟡" else "🌙"
        val correctEmoji = if (correctVal == CellType.SUN) "🟡" else "🌙"
        val sb = StringBuilder()
        sb.append("Deep Logic Explanation:\n")
        sb.append("If we temporarily place $startEmoji at Row ${startR+1}, Column ${startC+1}:\n\n")
        
        steps.forEachIndexed { index, step ->
            val stepEmoji = if (step.type == CellType.SUN) "🟡" else "🌙"
            sb.append("${index + 1}. First, this forces $stepEmoji at Row ${step.r+1}, Col ${step.c+1}:\n")
            sb.append("   → ${step.reason}\n\n")
        }
        
        sb.append("🔴 Contradiction reached!\n")
        sb.append("$contradiction\n\n")
        sb.append("Therefore, this cell must be $correctEmoji.")
        return sb.toString()
    }

    private fun formatContradictionTraceForMove(
        startR: Int,
        startC: Int,
        userVal: CellType,
        steps: List<TraceStep>,
        contradiction: String,
        correctVal: CellType
    ): String {
        val userEmoji = if (userVal == CellType.SUN) "🟡" else "🌙"
        val correctEmoji = if (correctVal == CellType.SUN) "🟡" else "🌙"
        val sb = StringBuilder()
        sb.append("Mistake Analysis:\n")
        sb.append("Your placed symbol $userEmoji at Row ${startR+1}, Column ${startC+1} leads directly to a contradiction:\n\n")
        
        steps.forEachIndexed { index, step ->
            val stepEmoji = if (step.type == CellType.SUN) "🟡" else "🌙"
            sb.append("${index + 1}. This forces $stepEmoji at Row ${step.r+1}, Col ${step.c+1}:\n")
            sb.append("   → ${step.reason}\n\n")
        }
        
        sb.append("🔴 Contradiction reached!\n")
        sb.append("$contradiction\n\n")
        sb.append("Therefore, this cell must be corrected to $correctEmoji.")
        return sb.toString()
    }
    
    private fun saveHistory() {
        moveHistory.add(grid.map { it.toList() })
    }

    fun getErrorCellsWithGrid(targetGrid: List<List<CellType>>): Set<Pair<Int, Int>> {
        val errors = mutableSetOf<Pair<Int, Int>>()
        for (r in 0 until 6) {
            if (r >= targetGrid.size) continue
            val sCount = targetGrid[r].count { it == CellType.SUN }
            val mCount = targetGrid[r].count { it == CellType.MOON }
            if (sCount > 3 || mCount > 3) {
                for (c in 0 until 6) {
                    if (c < targetGrid[r].size && targetGrid[r][c] != CellType.EMPTY) {
                        errors.add(Pair(r, c))
                    }
                }
            }
            var consS = 0
            var consM = 0
            for (c in 0 until 6) {
                if (c < targetGrid[r].size) {
                    val cellVal = targetGrid[r][c]
                    if (cellVal == CellType.SUN) { consS++; consM = 0 }
                    else if (cellVal == CellType.MOON) { consM++; consS = 0 }
                    else { consS = 0; consM = 0 }
                    
                    if (consS > 2 || consM > 2) {
                        errors.add(Pair(r, c))
                        errors.add(Pair(r, c - 1))
                        errors.add(Pair(r, c - 2))
                    }
                }
            }
        }
        for (c in 0 until 6) {
            var sCount = 0
            var mCount = 0
            for (r in 0 until 6) {
                if (r < targetGrid.size && c < targetGrid[r].size) {
                    if (targetGrid[r][c] == CellType.SUN) sCount++
                    if (targetGrid[r][c] == CellType.MOON) mCount++
                }
            }
            if (sCount > 3 || mCount > 3) {
                for (r in 0 until 6) {
                    if (r < targetGrid.size && c < targetGrid[r].size && targetGrid[r][c] != CellType.EMPTY) {
                        errors.add(Pair(r, c))
                    }
                }
            }
            var consS = 0
            var consM = 0
            for (r in 0 until 6) {
                if (r < targetGrid.size && c < targetGrid[r].size) {
                    val cellVal = targetGrid[r][c]
                    if (cellVal == CellType.SUN) { consS++; consM = 0 }
                    else if (cellVal == CellType.MOON) { consM++; consS = 0 }
                    else { consS = 0; consM = 0 }
                    
                    if (consS > 2 || consM > 2) {
                        errors.add(Pair(r, c))
                        errors.add(Pair(r - 1, c))
                        errors.add(Pair(r - 2, c))
                    }
                }
            }
        }
        return errors
    }

    fun getErrorCells(): Set<Pair<Int, Int>> {
        return getErrorCellsWithGrid(getActiveGrid())
    }

    fun getInvalidConstraintsWithGrid(targetGrid: List<List<CellType>>): Set<TangoConstraint> {
        val inv = mutableSetOf<TangoConstraint>()
        for (vc in currentLevel.vConstraints) {
            if (vc.row < targetGrid.size && vc.col + 1 < targetGrid[vc.row].size) {
                val c1 = targetGrid[vc.row][vc.col]
                val c2 = targetGrid[vc.row][vc.col + 1]
                if (c1 != CellType.EMPTY && c2 != CellType.EMPTY) {
                    if (vc.type == ConstraintType.EQUALS && c1 != c2) inv.add(vc)
                    if (vc.type == ConstraintType.CROSS && c1 == c2) inv.add(vc)
                }
            }
        }
        for (hc in currentLevel.hConstraints) {
            if (hc.row + 1 < targetGrid.size && hc.col < targetGrid[hc.row].size) {
                val c1 = targetGrid[hc.row][hc.col]
                val c2 = targetGrid[hc.row + 1][hc.col]
                if (c1 != CellType.EMPTY && c2 != CellType.EMPTY) {
                    if (hc.type == ConstraintType.EQUALS && c1 != c2) inv.add(hc)
                    if (hc.type == ConstraintType.CROSS && c1 == c2) inv.add(hc)
                }
            }
        }
        return inv
    }

    fun getInvalidConstraints(): Set<TangoConstraint> {
        return getInvalidConstraintsWithGrid(getActiveGrid())
    }
    
    private fun checkWin() {
        isWon = false
        if (grid.any { row -> row.any { it == CellType.EMPTY } }) return
        if (getErrorCellsWithGrid(grid).isNotEmpty()) return
        if (getInvalidConstraintsWithGrid(grid).isNotEmpty()) return
        isWon = true
        playSound(1046.5, 200)
        timerJob?.cancel()
        
        val previousBest = prefs.getLong("best_time_$currentLevelNumber", 0L)
        if (previousBest == 0L || timeSpent < previousBest) {
            prefs.edit().putLong("best_time_$currentLevelNumber", timeSpent).apply()
            bestTime = timeSpent
        }
        
        showWinOverlay = true
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TangoApp() {
    val viewModel: TangoViewModel = viewModel()
    var showHowToPlay by remember { mutableStateOf(false) }

    if (showHowToPlay) {
        AlertDialog(
            onDismissRequest = { showHowToPlay = false },
            title = { Text("How to play", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("• Fill the grid so that each cell contains either a Sun or a Moon.")
                    Text("• No more than 2 Suns or Moons may be next to each other, either vertically or horizontally.")
                    Text("• Each row (and column) must contain the same number of Suns and Moons.")
                    Text("• Cells separated by an = sign must be of the same type.")
                    Text("• Cells separated by a × sign must be of the opposite type.")
                    Text("• Each puzzle has one right answer and can be solved via deduction (you should never have to make a guess).")
                }
            },
            confirmButton = {
                TextButton(onClick = { showHowToPlay = false }) { Text("Got it!") }
            }
        )
    }

    if (viewModel.showWinOverlay) {
        AlertDialog(
            onDismissRequest = { viewModel.showWinOverlay = false },
            title = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🎉 Challenge Complete!", fontWeight = FontWeight.Bold, color = Color(0xFF1976D2))
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Congratulations! You solved Challenge ${"%02d".format(viewModel.currentLevelNumber)}.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    
                    val mins = viewModel.timeSpent / 60
                    val secs = viewModel.timeSpent % 60
                    val timeStr = "${"%02d".format(mins)}:${"%02d".format(secs)}"
                    
                    val bMins = viewModel.bestTime / 60
                    val bSecs = viewModel.bestTime % 60
                    val bestTimeStr = "${"%02d".format(bMins)}:${"%02d".format(bSecs)}"
                    
                    val isNewRecord = viewModel.timeSpent == viewModel.bestTime
                    
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Your Time", fontSize = 12.sp, color = Color(0xFF6B655F))
                            Text(timeStr, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.ExtraBold, color = Color(0xFF1976D2))
                            
                            if (isNewRecord) {
                                Box(
                                    modifier = Modifier
                                        .background(Color(0xFFFFD54F), RoundedCornerShape(16.dp))
                                        .padding(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Text("🏆 NEW PERSONAL BEST!", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFE65100))
                                }
                            } else {
                                Divider(color = Color(0xFFEAE8E3), thickness = 1.dp)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Best Record:", fontSize = 12.sp, color = Color(0xFF6B655F))
                                    Text(bestTimeStr, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2C2A29))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.nextLevel() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                ) {
                    Text("Next Challenge")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.showWinOverlay = false }) {
                    Text("Close", color = Color(0xFF6B655F))
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color(0xFFFDFBFA),
            topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = { 
                    Text("Tango Game", fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp) 
                },
                actions = {
                    TextButton(onClick = { viewModel.toggleSound() }) {
                        Text(if (viewModel.soundEnabled) "ON" else "OFF", color = Color(0xFF6B655F), fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = { showHowToPlay = true }) {
                        Icon(Icons.Default.Info, contentDescription = "How to play", tint = Color(0xFF6B655F))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Challenge ${"%02d".format(viewModel.currentLevelNumber)}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2C2A29)
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Difficulty Badge
                        val difficulty = viewModel.getLevelDifficulty()
                        Box(
                            modifier = Modifier
                                .background(difficulty.color.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                difficulty.label,
                                color = difficulty.color,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        
                        // Best Time
                        if (viewModel.bestTime > 0L) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = "Best Time",
                                    tint = Color(0xFFFFBF00),
                                    modifier = Modifier.size(12.dp)
                                )
                                val bMins = viewModel.bestTime / 60
                                val bSecs = viewModel.bestTime % 60
                                Text(
                                    "Best: ${"%02d".format(bMins)}:${"%02d".format(bSecs)}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF6B655F)
                                )
                            }
                        } else {
                            Text(
                                "Best: --:--",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFB1ABA3)
                            )
                        }
                    }
                }
                
                Column(horizontalAlignment = Alignment.End) {
                    val mins = viewModel.timeSpent / 60
                    val secs = viewModel.timeSpent % 60
                    Text(
                        "${"%02d".format(mins)}:${"%02d".format(secs)}", 
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1976D2)
                    )
                    Text(
                        "Current time",
                        fontSize = 10.sp,
                        color = Color(0xFF8D877F)
                    )
                }
            }
            
            Spacer(Modifier.weight(1f))
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .shadow(elevation = 8.dp, shape = RoundedCornerShape(16.dp), clip = false)
            ) {
                TangoBoard(
                    grid = viewModel.grid,
                    level = viewModel.currentLevel,
                    errorCells = viewModel.getErrorCells(),
                    errorConstraints = viewModel.getInvalidConstraints(),
                    violatedCells = viewModel.getTripleViolationCells(),
                    targetCell = viewModel.targetCell,
                    sourceCells = viewModel.sourceCells,
                    onCellClick = viewModel::onCellClick
                )
            }
            
            if (viewModel.hintExplanation.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFFE4E1DB), RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFAF9F6)),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Hint:",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF2C2A29)
                            )
                            IconButton(
                                onClick = { viewModel.clearHint() },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = androidx.compose.material.icons.Icons.Default.Close,
                                    contentDescription = "Close Hint",
                                    tint = Color(0xFF8D877F),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        
                        Text(
                            text = viewModel.hintExplanation,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF2C2A29),
                            lineHeight = 20.sp
                        )
                        
                        if (viewModel.activeHint != null) {
                            val target = Pair(viewModel.activeHint!!.row, viewModel.activeHint!!.col)
                            val isMistakeCorrection = viewModel.grid[target.first][target.second] != CellType.EMPTY
                            Button(
                                onClick = { viewModel.applyActiveHint() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White,
                                    contentColor = Color(0xFF6B655F)
                                ),
                                border = BorderStroke(1.dp, Color(0xFFB1ABA3)),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text(
                                    text = if (isMistakeCorrection) "Correct it" else "Show me",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
            
            Spacer(Modifier.weight(1f))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = { viewModel.replayLevel() },
                    modifier = Modifier.weight(1f).height(56.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEFECE7),
                        contentColor = Color(0xFF6B655F)
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                ) {
                    Text("Replay", fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                }
                Button(
                    onClick = { viewModel.undo() },
                    enabled = viewModel.moveHistory.isNotEmpty() && !viewModel.isWon,
                    modifier = Modifier.weight(1f).height(56.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEFECE7),
                        contentColor = Color(0xFF6B655F),
                        disabledContainerColor = Color(0xFFF5F4F1),
                        disabledContentColor = Color(0xFFB1ABA3)
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                ) {
                    Text("Undo", fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                }
                Button(
                    onClick = { viewModel.hint() },
                    enabled = !viewModel.isWon,
                    modifier = Modifier.weight(1f).height(56.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEFECE7),
                        contentColor = Color(0xFF6B655F),
                        disabledContainerColor = Color(0xFFF5F4F1),
                        disabledContentColor = Color(0xFFB1ABA3)
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                ) {
                    Text("Hint", fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                }
            }
            
            Spacer(Modifier.height(16.dp))
            
            Button(
                onClick = { viewModel.nextLevel() },
                enabled = viewModel.isWon,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1976D2),
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFFF5F4F1),
                    disabledContentColor = Color(0xFFB1ABA3)
                ),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 0.dp,
                    pressedElevation = 0.dp,
                    disabledElevation = 0.dp
                )
            ) {
                Text("Next Level", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    ConfettiEffect(isTriggered = viewModel.isWon)
    } // Closes Box
} // Closes TangoApp

@Composable
fun TangoBoard(
    grid: List<List<CellType>>, 
    level: TangoLevel, 
    errorCells: Set<Pair<Int, Int>>,
    errorConstraints: Set<TangoConstraint>,
    violatedCells: Set<Pair<Int, Int>> = emptySet(),
    targetCell: Pair<Int, Int>? = null,
    sourceCells: Set<Pair<Int, Int>> = emptySet(),
    onCellClick: (Int, Int) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White, RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xFFE4E1DB), RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
    ) {
        Column(Modifier.fillMaxSize()) {
            for (r in 0 until 6) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    for (c in 0 until 6) {
                        val isShaded = level.shadedCells.contains(Pair(r, c))
                        val isError = errorCells.contains(Pair(r, c))
                        val isTarget = targetCell == Pair(r, c)
                        val isSource = sourceCells.contains(Pair(r, c))
                        val isLocked = level.initial[r][c] != CellType.EMPTY
                        
                        val bgColor = if (isError) Color(0xFFFFEBEE) 
                                      else if (isTarget) Color.White
                                      else if (isSource) Color(0xFFE3F2FD) // light-blue background for sources
                                      else if (isLocked) Color(0xFFEAE8E3)
                                      else Color.White

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(bgColor)
                                .then(
                                    if (isTarget) Modifier.border(3.dp, Color(0xFF1976D2))
                                    else if (isSource) Modifier.border(2.dp, Color(0xFF90CAF9))
                                    else Modifier
                                )
                                .clickable(enabled = !isLocked) { onCellClick(r, c) },
                            contentAlignment = Alignment.Center
                        ) {
                            val type = if (grid.size > r && grid[r].size > c) grid[r][c] else CellType.EMPTY
                            Crossfade(targetState = type) { t ->
                                when (t) {
                                    CellType.SUN -> SunIcon(Modifier.fillMaxSize(0.55f))
                                    CellType.MOON -> MoonIcon(Modifier.fillMaxSize(0.55f))
                                    CellType.EMPTY -> Box(Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                }
            }
        }
        
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cellW = size.width / 6
            val cellH = size.height / 6
            
            for (i in 1..5) {
                drawLine(
                    color = Color(0xFFE4E1DB),
                    start = Offset(cellW * i, 0f),
                    end = Offset(cellW * i, size.height),
                    strokeWidth = 2.dp.toPx()
                )
                drawLine(
                    color = Color(0xFFE4E1DB),
                    start = Offset(0f, cellH * i),
                    end = Offset(size.width, cellH * i),
                    strokeWidth = 2.dp.toPx()
                )
            }

            // Draw beautiful translucent red overlay & solid border ONLY for violated three-in-a-row cells
            for (cell in violatedCells) {
                val r = cell.first
                val c = cell.second
                val padding = 4.dp.toPx()
                drawRoundRect(
                    color = Color(0x22F44336), // translucent soft red overlay
                    topLeft = Offset(c * cellW + padding, r * cellH + padding),
                    size = androidx.compose.ui.geometry.Size(cellW - padding * 2, cellH - padding * 2),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx(), 8.dp.toPx()),
                    style = Fill
                )
                drawRoundRect(
                    color = Color(0xFFE53935), // solid red tracking outline
                    topLeft = Offset(c * cellW + padding, r * cellH + padding),
                    size = androidx.compose.ui.geometry.Size(cellW - padding * 2, cellH - padding * 2),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx(), 8.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
            
            val textPaint = android.graphics.Paint().apply {
                isAntiAlias = true
                textSize = 24.sp.toPx()
                color = android.graphics.Color.parseColor("#807A73")
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                textAlign = android.graphics.Paint.Align.CENTER
            }
            val errorTextPaint = android.graphics.Paint(textPaint).apply {
                color = android.graphics.Color.parseColor("#D32F2F")
            }
            
            for (vc in level.vConstraints) {
                val cx = (vc.col + 1) * cellW
                val cy = vc.row * cellH + cellH / 2
                val isErr = errorConstraints.contains(vc)
                val p = if (isErr) errorTextPaint else textPaint
                drawContext.canvas.nativeCanvas.drawText(
                    if (vc.type == ConstraintType.EQUALS) "=" else "×",
                    cx, cy - (p.descent() + p.ascent()) / 2, p
                )
            }
            
            for (hc in level.hConstraints) {
                val cx = hc.col * cellW + cellW / 2
                val cy = (hc.row + 1) * cellH
                val isErr = errorConstraints.contains(hc)
                val p = if (isErr) errorTextPaint else textPaint
                drawContext.canvas.nativeCanvas.drawText(
                    if (hc.type == ConstraintType.EQUALS) "=" else "×",
                    cx, cy - (p.descent() + p.ascent()) / 2, p
                )
            }
        }
    }
}

@Composable
fun SunIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        drawCircle(
            color = Color(0xFFFFA726),
            style = Fill
        )
        drawCircle(
            color = Color(0xFFF57C00),
            style = Stroke(width = 3.dp.toPx())
        )
    }
}

@Composable
fun MoonIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val path1 = Path().apply {
            addOval(Rect(0f, 0f, size.width, size.height))
        }
        val path2 = Path().apply {
            addOval(Rect(size.width * 0.35f, -size.height * 0.15f, size.width * 1.35f, size.height * 0.85f))
        }
        val crescent = Path().apply {
            op(path1, path2, PathOperation.Difference)
        }
        drawPath(crescent, color = Color(0xFF42A5F5))
        drawPath(crescent, color = Color(0xFF1E88E5), style = Stroke(width = 3.dp.toPx()))
    }
}

class Particle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var color: Color,
    var size: Float,
    var rotation: Float,
    var rotSpeed: Float
)

@Composable
fun ConfettiEffect(isTriggered: Boolean) {
    if (!isTriggered) return

    val particles = remember { mutableListOf<Particle>() }
    var frame by remember { mutableLongStateOf(0L) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()

        LaunchedEffect(isTriggered, width, height) {
            if (isTriggered && width > 0f && height > 0f) {
                particles.clear()
                val colors = listOf(Color(0xFFE53935), Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFFFDD835), Color(0xFF8E24AA))
                for (i in 0..150) {
                    particles.add(
                        Particle(
                            x = width / 2f + (Math.random().toFloat() - 0.5f) * 100f,
                            y = height / 3f,
                            vx = (Math.random().toFloat() - 0.5f) * 30f,
                            vy = -(Math.random().toFloat() * 20f + 10f),
                            color = colors.random(),
                            size = (Math.random() * 25 + 10).toFloat(),
                            rotation = (Math.random() * 360).toFloat(),
                            rotSpeed = (Math.random() * 15 - 7.5f).toFloat()
                        )
                    )
                }
                
                while(particles.any { it.y < height + 100f }) {
                    withFrameNanos { time ->
                        for (p in particles) {
                            p.x += p.vx
                            p.vy += 0.5f // gravity
                            p.y += p.vy
                            p.rotation += p.rotSpeed
                        }
                        frame = time
                    }
                }
            }
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            frame // Read state to trigger recomposition
            for (p in particles) {
                withTransform({
                    translate(top = p.y, left = p.x)
                    rotate(p.rotation, pivot = androidx.compose.ui.geometry.Offset(p.size/2f, p.size*0.6f/2f))
                }) {
                    drawRect(
                        color = p.color,
                        topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                        size = androidx.compose.ui.geometry.Size(p.size, p.size * 0.6f)
                    )
                }
            }
        }
    }
}
