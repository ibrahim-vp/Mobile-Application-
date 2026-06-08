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

enum class CellType { EMPTY, SUN, MOON }
enum class ConstraintType { EQUALS, CROSS }
data class TangoConstraint(val row: Int, val col: Int, val type: ConstraintType)

data class TangoLevel(
    val id: Int,
    val levelNumber: Int,
    val solution: List<List<CellType>>,
    val initial: List<List<CellType>>,
    val vConstraints: List<TangoConstraint>,
    val hConstraints: List<TangoConstraint>,
    val shadedCells: Set<Pair<Int, Int>>
)

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

    var currentLevelNumber by mutableIntStateOf(1)
        private set

    var currentLevel by mutableStateOf<TangoLevel>(LevelGenerator.generateLevel(currentLevelNumber))
        private set
        
    var grid by mutableStateOf<List<List<CellType>>>(emptyList())
    var moveHistory = mutableListOf<List<List<CellType>>>()
    
    var timeSpent by mutableLongStateOf(0L)
    var isWon by mutableStateOf(false)
    var hintCount by mutableIntStateOf(0)
    
    var hintExplanation by mutableStateOf("")
        private set
    var targetCell by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    var sourceCells by mutableStateOf<Set<Pair<Int, Int>>>(emptySet())
        private set
    
    var showWinOverlay by mutableStateOf(false)
    
    var soundEnabled by mutableStateOf(prefs.getBoolean("sound_enabled", true))
        private set
        
    fun playSound(freq: Double, durationMs: Int) {
        // Disabled to prevent AppOps attributionTag spam
    }
    
    fun toggleSound() {
        soundEnabled = !soundEnabled
        prefs.edit().putBoolean("sound_enabled", soundEnabled).apply()
    }
    
    private var timerJob: kotlinx.coroutines.Job? = null
    
    init {
        loadCurrentLevel()
    }
    
    fun loadCurrentLevel() {
        showWinOverlay = false
        clearHint()
        grid = currentLevel.initial.map { it.toList() }
        moveHistory.clear()
        timeSpent = 0
        isWon = false
        hintCount = 0
        startTimer()
    }
    
    fun nextLevel() {
        showWinOverlay = false
        currentLevelNumber++
        currentLevel = LevelGenerator.generateLevel(currentLevelNumber)
        loadCurrentLevel()
    }
    
    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (!isWon) {
                kotlinx.coroutines.delay(1000)
                timeSpent++
            }
        }
    }
    
    private fun clearHint() {
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
        
        val newGrid = grid.map { it.toMutableList() }
        newGrid[r][c] = when (grid[r][c]) {
            CellType.EMPTY -> CellType.SUN
            CellType.SUN -> CellType.MOON
            CellType.MOON -> CellType.EMPTY
        }
        grid = newGrid
        checkWin()
    }
    
    fun undo() {
        if (isWon || moveHistory.isEmpty()) return
        playSound(660.0, 30)
        clearHint()
        val last = moveHistory.removeLast()
        grid = last
        checkWin()
    }
    
    private fun getOpposite(c: CellType): CellType {
        if (c == CellType.SUN) return CellType.MOON
        if (c == CellType.MOON) return CellType.SUN
        return CellType.EMPTY
    }
    
    private fun applyHint(r: Int, c: Int, change: CellType, sources: Set<Pair<Int, Int>>, expl: String) {
        saveHistory()
        hintExplanation = expl
        targetCell = Pair(r, c)
        sourceCells = sources
        val newGrid = grid.map { it.toMutableList() }
        newGrid[r][c] = change
        grid = newGrid
        hintCount++
        checkWin()
    }
    
    fun hint() {
        if (isWon) return
        playSound(660.0, 30)
        clearHint()
        
        // 1. Sandwich Rule (Row)
        for (r in 0 until 6) {
            for (c in 0 until 4) {
                if (grid[r][c] != CellType.EMPTY && grid[r][c] == grid[r][c+2] && grid[r][c+1] == CellType.EMPTY) {
                    applyHint(r, c+1, getOpposite(grid[r][c]), 
                        setOf(Pair(r, c), Pair(r, c+2)), 
                        "Sandwich Rule: Cannot place 3 similar symbols consecutively, so the opposite symbol must be placed between them.")
                    return
                }
            }
        }
        // 1. Sandwich Rule (Col)
        for (c in 0 until 6) {
            for (r in 0 until 4) {
                if (grid[r][c] != CellType.EMPTY && grid[r][c] == grid[r+2][c] && grid[r+1][c] == CellType.EMPTY) {
                    applyHint(r+1, c, getOpposite(grid[r][c]), 
                        setOf(Pair(r, c), Pair(r+2, c)), 
                        "Sandwich Rule: Cannot place 3 similar symbols consecutively, so the opposite symbol must be placed between them.")
                    return
                }
            }
        }

        // 2. Pair Rule (Row)
        for (r in 0 until 6) {
            for (c in 0 until 5) {
                if (grid[r][c] != CellType.EMPTY && grid[r][c] == grid[r][c+1]) {
                    if (c > 0 && grid[r][c-1] == CellType.EMPTY) {
                        applyHint(r, c-1, getOpposite(grid[r][c]), setOf(Pair(r, c), Pair(r, c+1)), "Pair Rule: Cannot place 3 similar symbols consecutively.")
                        return
                    }
                    if (c < 4 && grid[r][c+2] == CellType.EMPTY) {
                        applyHint(r, c+2, getOpposite(grid[r][c]), setOf(Pair(r, c), Pair(r, c+1)), "Pair Rule: Cannot place 3 similar symbols consecutively.")
                        return
                    }
                }
            }
        }
        
        // 2. Pair Rule (Col)
        for (c in 0 until 6) {
            for (r in 0 until 5) {
                if (grid[r][c] != CellType.EMPTY && grid[r][c] == grid[r+1][c]) {
                    if (r > 0 && grid[r-1][c] == CellType.EMPTY) {
                        applyHint(r-1, c, getOpposite(grid[r][c]), setOf(Pair(r, c), Pair(r+1, c)), "Pair Rule: Cannot place 3 similar symbols consecutively.")
                        return
                    }
                    if (r < 4 && grid[r+2][c] == CellType.EMPTY) {
                        applyHint(r+2, c, getOpposite(grid[r][c]), setOf(Pair(r, c), Pair(r+1, c)), "Pair Rule: Cannot place 3 similar symbols consecutively.")
                        return
                    }
                }
            }
        }

        // 3. Balance Rule (Row)
        for (r in 0 until 6) {
            val suns = grid[r].count { it == CellType.SUN }
            val moons = grid[r].count { it == CellType.MOON }
            if (suns == 3 && moons < 3) {
                val emptyCol = grid[r].indexOfFirst { it == CellType.EMPTY }
                if (emptyCol != -1) {
                    applyHint(r, emptyCol, CellType.MOON, grid[r].mapIndexedNotNull { i, cellType -> if (cellType == CellType.SUN) Pair(r, i) else null }.toSet(), "Balance Rule: The row already has 3 suns, so the remaining cells must be moons.")
                    return
                }
            }
            if (moons == 3 && suns < 3) {
                val emptyCol = grid[r].indexOfFirst { it == CellType.EMPTY }
                if (emptyCol != -1) {
                    applyHint(r, emptyCol, CellType.SUN, grid[r].mapIndexedNotNull { i, cellType -> if (cellType == CellType.MOON) Pair(r, i) else null }.toSet(), "Balance Rule: The row already has 3 moons, so the remaining cells must be suns.")
                    return
                }
            }
        }
        
        // 3. Balance Rule (Col)
        for (c in 0 until 6) {
            val suns = (0 until 6).count { grid[it][c] == CellType.SUN }
            val moons = (0 until 6).count { grid[it][c] == CellType.MOON }
            if (suns == 3 && moons < 3) {
                val emptyRow = (0 until 6).firstOrNull { grid[it][c] == CellType.EMPTY }
                if (emptyRow != null) {
                    val sources = (0 until 6).mapNotNull { if (grid[it][c] == CellType.SUN) Pair(it, c) else null }.toSet()
                    applyHint(emptyRow, c, CellType.MOON, sources, "Balance Rule: The column already has 3 suns, so the remaining cells must be moons.")
                    return
                }
            }
            if (moons == 3 && suns < 3) {
                val emptyRow = (0 until 6).firstOrNull { grid[it][c] == CellType.EMPTY }
                if (emptyRow != null) {
                    val sources = (0 until 6).mapNotNull { if (grid[it][c] == CellType.MOON) Pair(it, c) else null }.toSet()
                    applyHint(emptyRow, c, CellType.SUN, sources, "Balance Rule: The column already has 3 moons, so the remaining cells must be suns.")
                    return
                }
            }
        }

        // 4. Relation Rules (vConstraints)
        for (vc in currentLevel.vConstraints) {
            val r = vc.row
            val c = vc.col
            val left = grid[r][c]
            val right = grid[r][c+1]
            if (left != CellType.EMPTY && right == CellType.EMPTY) {
                val type = if (vc.type == ConstraintType.EQUALS) left else getOpposite(left)
                applyHint(r, c+1, type, setOf(Pair(r, c)), if (vc.type == ConstraintType.EQUALS) "Relation Rule: The cells are connected by '=', so they must be the same." else "Relation Rule: The cells are connected by '×', so they must be different.")
                return
            }
            if (left == CellType.EMPTY && right != CellType.EMPTY) {
                val type = if (vc.type == ConstraintType.EQUALS) right else getOpposite(right)
                applyHint(r, c, type, setOf(Pair(r, c+1)), if (vc.type == ConstraintType.EQUALS) "Relation Rule: The cells are connected by '=', so they must be the same." else "Relation Rule: The cells are connected by '×', so they must be different.")
                return
            }
        }
        
        // 4. Relation Rules (hConstraints)
        for (hc in currentLevel.hConstraints) {
            val r = hc.row
            val c = hc.col
            val top = grid[r][c]
            val bottom = grid[r+1][c]
            if (top != CellType.EMPTY && bottom == CellType.EMPTY) {
                val type = if (hc.type == ConstraintType.EQUALS) top else getOpposite(top)
                applyHint(r+1, c, type, setOf(Pair(r, c)), if (hc.type == ConstraintType.EQUALS) "Relation Rule: The cells are connected by '=', so they must be the same." else "Relation Rule: The cells are connected by '×', so they must be different.")
                return
            }
            if (top == CellType.EMPTY && bottom != CellType.EMPTY) {
                val type = if (hc.type == ConstraintType.EQUALS) bottom else getOpposite(bottom)
                applyHint(r, c, type, setOf(Pair(r+1, c)), if (hc.type == ConstraintType.EQUALS) "Relation Rule: The cells are connected by '=', so they must be the same." else "Relation Rule: The cells are connected by '×', so they must be different.")
                return
            }
        }

        // Fallback for advanced thinking
        for (r in 0 until 6) {
            for (c in 0 until 6) {
                if (grid[r][c] == CellType.EMPTY) {
                    val sol = currentLevel.solution[r][c]
                    val typeStr = if (sol == CellType.SUN) "Sun" else "Moon"
                    applyHint(r, c, sol, emptySet(), "Advanced logic deduction: by testing possibilities, placing the opposite leads to a contradiction. So it must be $typeStr.")
                    return
                } else if (grid[r][c] != currentLevel.solution[r][c] && currentLevel.initial[r][c] == CellType.EMPTY) {
                     val sol = currentLevel.solution[r][c]
                     applyHint(r, c, sol, emptySet(), "This placed cell is incorrect and leads to a contradiction.")
                     return
                }
            }
        }
    }
    
    private fun saveHistory() {
        moveHistory.add(grid.map { it.toList() })
    }

    fun getErrorCells(): Set<Pair<Int, Int>> {
        val errors = mutableSetOf<Pair<Int, Int>>()
        for (r in 0 until 6) {
            val sCount = grid[r].count { it == CellType.SUN }
            val mCount = grid[r].count { it == CellType.MOON }
            if (sCount > 3 || mCount > 3) {
                for (c in 0 until 6) if (grid[r][c] != CellType.EMPTY) errors.add(Pair(r, c))
            }
            var consS = 0
            var consM = 0
            for (c in 0 until 6) {
                if(grid[r][c] == CellType.SUN) { consS++; consM = 0 }
                else if(grid[r][c] == CellType.MOON) { consM++; consS = 0 }
                else { consS = 0; consM = 0 }
                
                if(consS > 2 || consM > 2) {
                     errors.add(Pair(r, c))
                     errors.add(Pair(r, c-1))
                     errors.add(Pair(r, c-2))
                }
            }
        }
        for (c in 0 until 6) {
            var sCount = 0
            var mCount = 0
            for (r in 0 until 6) {
                if (grid[r][c] == CellType.SUN) sCount++
                if (grid[r][c] == CellType.MOON) mCount++
            }
            if (sCount > 3 || mCount > 3) {
                for (r in 0 until 6) if (grid[r][c] != CellType.EMPTY) errors.add(Pair(r, c))
            }
            var consS = 0
            var consM = 0
            for (r in 0 until 6) {
                if(grid[r][c] == CellType.SUN) { consS++; consM = 0 }
                else if(grid[r][c] == CellType.MOON) { consM++; consS = 0 }
                else { consS = 0; consM = 0 }
                
                if(consS > 2 || consM > 2) {
                     errors.add(Pair(r, c))
                     errors.add(Pair(r-1, c))
                     errors.add(Pair(r-2, c))
                }
            }
        }
        return errors
    }

    fun getInvalidConstraints(): Set<TangoConstraint> {
        val inv = mutableSetOf<TangoConstraint>()
        for (vc in currentLevel.vConstraints) {
            val c1 = grid[vc.row][vc.col]
            val c2 = grid[vc.row][vc.col+1]
            if (c1 != CellType.EMPTY && c2 != CellType.EMPTY) {
                if (vc.type == ConstraintType.EQUALS && c1 != c2) inv.add(vc)
                if (vc.type == ConstraintType.CROSS && c1 == c2) inv.add(vc)
            }
        }
        for (hc in currentLevel.hConstraints) {
            val c1 = grid[hc.row][hc.col]
            val c2 = grid[hc.row+1][hc.col]
            if (c1 != CellType.EMPTY && c2 != CellType.EMPTY) {
                if (hc.type == ConstraintType.EQUALS && c1 != c2) inv.add(hc)
                if (hc.type == ConstraintType.CROSS && c1 == c2) inv.add(hc)
            }
        }
        return inv
    }
    
    private fun checkWin() {
        isWon = false
        if (grid.any { row -> row.any { it == CellType.EMPTY } }) return
        if (getErrorCells().isNotEmpty()) return
        if (getInvalidConstraints().isNotEmpty()) return
        isWon = true
        playSound(1046.5, 200)
        timerJob?.cancel()
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
                Text(
                    "Challenge ${"%02d".format(viewModel.currentLevelNumber)}",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2C2A29)
                )
                
                val mins = viewModel.timeSpent / 60
                val secs = viewModel.timeSpent % 60
                Text(
                    "${"%02d".format(mins)}:${"%02d".format(secs)}", 
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF1976D2)
                )
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
                    targetCell = viewModel.targetCell,
                    sourceCells = viewModel.sourceCells,
                    onCellClick = viewModel::onCellClick
                )
            }
            
            if (viewModel.hintExplanation.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = viewModel.hintExplanation,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF1976D2),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFE3F2FD), RoundedCornerShape(8.dp))
                        .padding(12.dp)
                )
            }
            
            Spacer(Modifier.weight(1f))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = { viewModel.loadCurrentLevel() },
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
                                      else if (isTarget) Color(0xFFFFF59D)
                                      else if (isSource) Color(0xFFBBDEFB)
                                      else if (isLocked) Color(0xFFEAE8E3)
                                      else Color.White

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(bgColor)
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
