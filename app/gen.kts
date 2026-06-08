import kotlin.random.Random

enum class CellType { EMPTY, SUN, MOON }
enum class ConstraintType { EQUALS, CROSS }
data class TangoConstraint(val row: Int, val col: Int, val type: ConstraintType)

fun getOpposite(vt: CellType) = if(vt == CellType.SUN) CellType.MOON else CellType.SUN

fun isValid(grid: Array<Array<CellType>>, r: Int, c: Int, type: CellType): Boolean {
    grid[r][c] = type
    
    // Check row counts
    var suns = 0; var moons = 0
    for(i in 0 until 6) {
        if(grid[r][i] == CellType.SUN) suns++
        if(grid[r][i] == CellType.MOON) moons++
    }
    if(suns > 3 || moons > 3) { grid[r][c] = CellType.EMPTY; return false }
    
    // Check col counts
    suns = 0; moons = 0
    for(i in 0 until 6) {
        if(grid[i][c] == CellType.SUN) suns++
        if(grid[i][c] == CellType.MOON) moons++
    }
    if(suns > 3 || moons > 3) { grid[r][c] = CellType.EMPTY; return false }
    
    // Check consecutive row
    var consS = 0; var consM = 0
    for(i in 0 until 6) {
        if(grid[r][i] == CellType.SUN) { consS++; consM = 0 }
        else if(grid[r][i] == CellType.MOON) { consM++; consS = 0 }
        else { consS = 0; consM = 0 }
        if(consS > 2 || consM > 2) { grid[r][c] = CellType.EMPTY; return false }
    }
    
    // Check consecutive col
    consS = 0; consM = 0
    for(i in 0 until 6) {
        if(grid[i][c] == CellType.SUN) { consS++; consM = 0 }
        else if(grid[i][c] == CellType.MOON) { consM++; consS = 0 }
        else { consS = 0; consM = 0 }
        if(consS > 2 || consM > 2) { grid[r][c] = CellType.EMPTY; return false }
    }
    
    grid[r][c] = CellType.EMPTY
    return true
}

fun solve(grid: Array<Array<CellType>>): Boolean {
    for(r in 0 until 6) {
        for(c in 0 until 6) {
            if(grid[r][c] == CellType.EMPTY) {
                val types = mutableListOf(CellType.SUN, CellType.MOON)
                types.shuffle()
                for(type in types) {
                    if(isValid(grid, r, c, type)) {
                        grid[r][c] = type
                        if(solve(grid)) return true
                        grid[r][c] = CellType.EMPTY
                    }
                }
                return false
            }
        }
    }
    return true
}

// simple logic deduction:
// just apply the rules. If it changes, re-run, till stuck.
fun logicalSolve(grid: Array<Array<CellType>>, vCons: List<TangoConstraint>, hCons: List<TangoConstraint>): Boolean {
    var changed = true
    while(changed) {
        changed = false
        // 1. Sandwich
        for(r in 0 until 6) {
            for(c in 0 until 4) {
                if(grid[r][c] != CellType.EMPTY && grid[r][c] == grid[r][c+2] && grid[r][c+1] == CellType.EMPTY) {
                    grid[r][c+1] = getOpposite(grid[r][c])
                    changed = true
                }
            }
        }
        for(c in 0 until 6) {
            for(r in 0 until 4) {
                if(grid[r][c] != CellType.EMPTY && grid[r][c] == grid[r+2][c] && grid[r+1][c] == CellType.EMPTY) {
                    grid[r+1][c] = getOpposite(grid[r][c])
                    changed = true
                }
            }
        }
        // 2. Pair
        for(r in 0 until 6) {
            for(c in 0 until 5) {
                if(grid[r][c] != CellType.EMPTY && grid[r][c] == grid[r][c+1]) {
                    if(c > 0 && grid[r][c-1] == CellType.EMPTY) { grid[r][c-1] = getOpposite(grid[r][c]); changed = true }
                    if(c < 4 && grid[r][c+2] == CellType.EMPTY) { grid[r][c+2] = getOpposite(grid[r][c]); changed = true }
                }
            }
        }
        for(c in 0 until 6) {
            for(r in 0 until 5) {
                if(grid[r][c] != CellType.EMPTY && grid[r][c] == grid[r+1][c]) {
                    if(r > 0 && grid[r-1][c] == CellType.EMPTY) { grid[r-1][c] = getOpposite(grid[r][c]); changed = true }
                    if(r < 4 && grid[r+2][c] == CellType.EMPTY) { grid[r+2][c] = getOpposite(grid[r][c]); changed = true }
                }
            }
        }
        // 3. Balance
        for(r in 0 until 6) {
            val s = grid[r].count { it == CellType.SUN }
            val m = grid[r].count { it == CellType.MOON }
            if(s == 3 && m < 3) {
                for(c in 0 until 6) if(grid[r][c] == CellType.EMPTY) { grid[r][c] = CellType.MOON; changed = true }
            } else if (m == 3 && s < 3) {
                for(c in 0 until 6) if(grid[r][c] == CellType.EMPTY) { grid[r][c] = CellType.SUN; changed = true }
            }
        }
        for(c in 0 until 6) {
            val s = (0..5).count { grid[it][c] == CellType.SUN }
            val m = (0..5).count { grid[it][c] == CellType.MOON }
            if(s == 3 && m < 3) {
                for(r in 0 until 6) if(grid[r][c] == CellType.EMPTY) { grid[r][c] = CellType.MOON; changed = true }
            } else if (m == 3 && s < 3) {
                for(r in 0 until 6) if(grid[r][c] == CellType.EMPTY) { grid[r][c] = CellType.SUN; changed = true }
            }
        }
        // 4. constraints
        for(v in vCons) {
            val left = grid[v.row][v.col]
            val right = grid[v.row][v.col+1]
            if(left != CellType.EMPTY && right == CellType.EMPTY) {
                grid[v.row][v.col+1] = if(v.type == ConstraintType.EQUALS) left else getOpposite(left)
                changed = true
            } else if (left == CellType.EMPTY && right != CellType.EMPTY) {
                grid[v.row][v.col] = if(v.type == ConstraintType.EQUALS) right else getOpposite(right)
                changed = true
            }
        }
        for(h in hCons) {
            val top = grid[h.row][h.col]
            val bottom = grid[h.row+1][h.col]
            if(top != CellType.EMPTY && bottom == CellType.EMPTY) {
                grid[h.row+1][h.col] = if(h.type == ConstraintType.EQUALS) top else getOpposite(top)
                changed = true
            } else if (top == CellType.EMPTY && bottom != CellType.EMPTY) {
                grid[h.row][h.col] = if(h.type == ConstraintType.EQUALS) bottom else getOpposite(bottom)
                changed = true
            }
        }
    }
    return grid.all { row -> row.all { it != CellType.EMPTY } }
}

fun main() {
    val grid = Array(6) { Array(6) { CellType.EMPTY } }
    solve(grid)
    for(r in 0 until 6) {
        println(grid[r].map { if(it == CellType.SUN) "S" else "M"}.joinToString(""))
    }
}
