package com.example

import kotlin.random.Random

object LevelGenerator {

    private fun getOpposite(vt: CellType) = if(vt == CellType.SUN) CellType.MOON else CellType.SUN

    private fun isValid(grid: Array<Array<CellType>>, r: Int, c: Int, type: CellType): Boolean {
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

    private fun solve(grid: Array<Array<CellType>>): Boolean {
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

    private fun hasContradiction(g: Array<Array<CellType>>, vCons: List<TangoConstraint>, hCons: List<TangoConstraint>): Boolean {
        for (r in 0 until 6) {
            val s = g[r].count { it == CellType.SUN }
            val m = g[r].count { it == CellType.MOON }
            if (s > 3 || m > 3) return true
            
            var consS = 0; var consM = 0
            for (c in 0 until 6) {
                if (g[r][c] == CellType.SUN) { consS++; consM = 0 }
                else if (g[r][c] == CellType.MOON) { consM++; consS = 0 }
                else { consS = 0; consM = 0 }
                if (consS > 2 || consM > 2) return true
            }
        }
        for (c in 0 until 6) {
            val s = (0..5).count { g[it][c] == CellType.SUN }
            val m = (0..5).count { g[it][c] == CellType.MOON }
            if (s > 3 || m > 3) return true
            
            var consS = 0; var consM = 0
            for (r in 0 until 6) {
                if (g[r][c] == CellType.SUN) { consS++; consM = 0 }
                else if (g[r][c] == CellType.MOON) { consM++; consS = 0 }
                else { consS = 0; consM = 0 }
                if (consS > 2 || consM > 2) return true
            }
        }
        for (v in vCons) {
            val left = g[v.row][v.col]
            val right = g[v.row][v.col + 1]
            if (left != CellType.EMPTY && right != CellType.EMPTY) {
                if (v.type == ConstraintType.EQUALS && left != right) return true
                if (v.type == ConstraintType.CROSS && left == right) return true
            }
        }
        for (h in hCons) {
            val top = g[h.row][h.col]
            val bottom = g[h.row + 1][h.col]
            if (top != CellType.EMPTY && bottom != CellType.EMPTY) {
                if (h.type == ConstraintType.EQUALS && top != bottom) return true
                if (h.type == ConstraintType.CROSS && top == bottom) return true
            }
        }
        return false
    }

    private fun applyBasicRules(g: Array<Array<CellType>>, vCons: List<TangoConstraint>, hCons: List<TangoConstraint>): Boolean {
        var changed = true
        while(changed) {
            changed = false
            for (r in 0 until 6) {
                for (c in 0 until 4) {
                    if (g[r][c] != CellType.EMPTY && g[r][c] == g[r][c + 2] && g[r][c + 1] == CellType.EMPTY) {
                        g[r][c + 1] = getOpposite(g[r][c])
                        changed = true
                    }
                }
            }
            for (c in 0 until 6) {
                for (r in 0 until 4) {
                    if (g[r][c] != CellType.EMPTY && g[r][c] == g[r + 2][c] && g[r + 1][c] == CellType.EMPTY) {
                        g[r + 1][c] = getOpposite(g[r][c])
                        changed = true
                    }
                }
            }
            for (r in 0 until 6) {
                for (c in 0 until 5) {
                    if (g[r][c] != CellType.EMPTY && g[r][c] == g[r][c + 1]) {
                        if (c > 0 && g[r][c - 1] == CellType.EMPTY) {
                            g[r][c - 1] = getOpposite(g[r][c])
                            changed = true
                        }
                        if (c < 4 && g[r][c + 2] == CellType.EMPTY) {
                            g[r][c + 2] = getOpposite(g[r][c])
                            changed = true
                        }
                    }
                }
            }
            for (c in 0 until 6) {
                for (r in 0 until 5) {
                    if (g[r][c] != CellType.EMPTY && g[r][c] == g[r + 1][c]) {
                        if (r > 0 && g[r - 1][c] == CellType.EMPTY) {
                            g[r - 1][c] = getOpposite(g[r][c])
                            changed = true
                        }
                        if (r < 4 && g[r + 2][c] == CellType.EMPTY) {
                            g[r + 2][c] = getOpposite(g[r][c])
                            changed = true
                        }
                    }
                }
            }
            for (r in 0 until 6) {
                val s = g[r].count { it == CellType.SUN }
                val m = g[r].count { it == CellType.MOON }
                if (s == 3 && m < 3) {
                    for (c in 0 until 6) if (g[r][c] == CellType.EMPTY) {
                        g[r][c] = CellType.MOON
                        changed = true
                    }
                } else if (m == 3 && s < 3) {
                    for (c in 0 until 6) if (g[r][c] == CellType.EMPTY) {
                        g[r][c] = CellType.SUN
                        changed = true
                    }
                }
            }
            for (c in 0 until 6) {
                val s = (0..5).count { g[it][c] == CellType.SUN }
                val m = (0..5).count { g[it][c] == CellType.MOON }
                if (s == 3 && m < 3) {
                    for (r in 0 until 6) if (g[r][c] == CellType.EMPTY) {
                        g[r][c] = CellType.MOON
                        changed = true
                    }
                } else if (m == 3 && s < 3) {
                    for (r in 0 until 6) if (g[r][c] == CellType.EMPTY) {
                        g[r][c] = CellType.SUN
                        changed = true
                    }
                }
            }
            for (v in vCons) {
                val left = g[v.row][v.col]
                val right = g[v.row][v.col + 1]
                if (left != CellType.EMPTY && right == CellType.EMPTY) {
                    g[v.row][v.col + 1] = if (v.type == ConstraintType.EQUALS) left else getOpposite(left)
                    changed = true
                } else if (left == CellType.EMPTY && right != CellType.EMPTY) {
                    g[v.row][v.col] = if (v.type == ConstraintType.EQUALS) right else getOpposite(right)
                    changed = true
                }
            }
            for (h in hCons) {
                val top = g[h.row][h.col]
                val bottom = g[h.row + 1][h.col]
                if (top != CellType.EMPTY && bottom == CellType.EMPTY) {
                    g[h.row + 1][h.col] = if (h.type == ConstraintType.EQUALS) top else getOpposite(top)
                    changed = true
                } else if (top == CellType.EMPTY && bottom != CellType.EMPTY) {
                    g[h.row][h.col] = if (h.type == ConstraintType.EQUALS) bottom else getOpposite(bottom)
                    changed = true
                }
            }
            if (hasContradiction(g, vCons, hCons)) return false
        }
        return true
    }

    private fun logicalSolve(grid: Array<Array<CellType>>, vCons: List<TangoConstraint>, hCons: List<TangoConstraint>): Boolean {
        val g = Array(6) { r -> Array(6) { c -> grid[r][c] } }
        var globalChanged = true
        while (globalChanged) {
            globalChanged = false
            if (!applyBasicRules(g, vCons, hCons)) return false
            if (g.all { row -> row.all { it != CellType.EMPTY } }) return true
            
            for (r in 0 until 6) {
                for (c in 0 until 6) {
                    if (g[r][c] == CellType.EMPTY) {
                        val gSun = Array(6) { rr -> Array(6) { cc -> g[rr][cc] } }
                        gSun[r][c] = CellType.SUN
                        val sunValid = applyBasicRules(gSun, vCons, hCons)
                        
                        val gMoon = Array(6) { rr -> Array(6) { cc -> g[rr][cc] } }
                        gMoon[r][c] = CellType.MOON
                        val moonValid = applyBasicRules(gMoon, vCons, hCons)
                        
                        if (!sunValid && !moonValid) return false
                        
                        if (sunValid && !moonValid) {
                            g[r][c] = CellType.SUN
                            globalChanged = true
                        } else if (!sunValid && moonValid) {
                            g[r][c] = CellType.MOON
                            globalChanged = true
                        }
                        if (globalChanged) break
                    }
                }
                if (globalChanged) break
            }
        }
        return g.all { row -> row.all { it != CellType.EMPTY } }
    }

    fun generateLevel(levelId: Int): TangoLevel {
        var bestInitial: List<List<CellType>>? = null
        var bestSolution: List<List<CellType>>? = null
        var bestVCons: List<TangoConstraint>? = null
        var bestHCons: List<TangoConstraint>? = null
        var minGivens = Int.MAX_VALUE

        for(attempt in 0 until 10) {
            val grid = Array(6) { Array(6) { CellType.EMPTY } }
            solve(grid)
            
            val possibleV = mutableListOf<TangoConstraint>()
            for(r in 0 until 6) {
                for(c in 0 until 5) {
                    val type = if(grid[r][c] == grid[r][c+1]) ConstraintType.EQUALS else ConstraintType.CROSS
                    possibleV.add(TangoConstraint(r, c, type))
                }
            }
            val possibleH = mutableListOf<TangoConstraint>()
            for(r in 0 until 5) {
                for(c in 0 until 6) {
                    val type = if(grid[r][c] == grid[r+1][c]) ConstraintType.EQUALS else ConstraintType.CROSS
                    possibleH.add(TangoConstraint(r, c, type))
                }
            }
            possibleV.shuffle()
            possibleH.shuffle()
            
            val vCons = possibleV.take(Random.nextInt(1, 4))
            val hCons = possibleH.take(Random.nextInt(1, 4))

            val revealed = Array(6) { Array(6) { CellType.EMPTY } }
            val cells = mutableListOf<Pair<Int, Int>>()
            for(r in 0 until 6) for(c in 0 until 6) cells.add(Pair(r, c))
            cells.shuffle()

            for(cell in cells) {
                revealed[cell.first][cell.second] = grid[cell.first][cell.second]
                if(logicalSolve(revealed, vCons, hCons)) break
            }

            cells.shuffle()
            for((r, c) in cells) {
                if(revealed[r][c] != CellType.EMPTY) {
                    val backup = revealed[r][c]
                    revealed[r][c] = CellType.EMPTY
                    if(!logicalSolve(revealed, vCons, hCons)) {
                        revealed[r][c] = backup // put it back
                    }
                }
            }

            val givens = revealed.sumOf { row -> row.count { it != CellType.EMPTY } }
            if (givens < minGivens) {
                minGivens = givens
                bestInitial = revealed.map { it.toList() }.toList()
                bestSolution = grid.map { it.toList() }.toList()
                bestVCons = vCons
                bestHCons = hCons
            }
            if (minGivens <= 6) break
        }

        return TangoLevel(
            id = levelId,
            levelNumber = levelId,
            solution = bestSolution!!,
            initial = bestInitial!!,
            vConstraints = bestVCons!!,
            hConstraints = bestHCons!!,
            shadedCells = emptySet()
        )
    }
}
