package com.cs0x7f.min2phase; /**
    Copyright (C) 2015  Shuang Chen

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

/**
 * Rubik's Cube Solver.<br>
 * A much faster and smaller implemention of Two-Phase Algorithm.<br>
 * Symmetry is used to reduce memory used.<br>
 * Total Memory used is about 1MB.<br>
 * @author Shuang Chen
 */
public class Search {

    public static final boolean USE_TWIST_FLIP_PRUN = true;

    /**
     * 0: without extra pruning table
     * 1: full phase 1 pruning table (28M, for two-phase solver and optimal solver)
     * 2: full phase 1 pruning table (28M, for two-phase solver) + huge pruning table (2.0G, for optimal solver)
     */
    public static final int EXTRA_PRUN_LEVEL = 0;

    public static final boolean USF_FULL_PRUN = EXTRA_PRUN_LEVEL > 0;
    public static final boolean USF_HUGE_PRUN = EXTRA_PRUN_LEVEL > 1;

    //Options for research purpose.
    static final boolean TRY_PRE_MOVE = true;
    static final boolean TRY_INVERSE = true;
    static final boolean TRY_THREE_AXES = true;

    static final int MAX_DEPTH2 = EXTRA_PRUN_LEVEL > 0 ? 12 : 13;

    static final int PRE_IDX_MAX = TRY_PRE_MOVE ? 9 : 1;

    static boolean inited = false;

    private int[] move = new int[31];

    private int[][] corn0 = new int[6][PRE_IDX_MAX];
    private int[][] ud8e0 = new int[6][PRE_IDX_MAX];

    private CoordCube[] nodeUD = new CoordCube[21];
    private CoordCube[] nodeRL = new CoordCube[21];
    private CoordCube[] nodeFB = new CoordCube[21];

    private CoordCube[][] node0 = new CoordCube[6][PRE_IDX_MAX];

    private byte[] f = new byte[54];

    private long selfSym;
    private int preIdxMax;
    private int conjMask;
    private int urfIdx;
    private int preIdx;
    private int length1;
    private int depth1;
    private int maxDep2;
    private int sol;
    private String solution;
    private long probe;
    private long probeMax;
    private long probeMin;
    private int verbose;
    private CubieCube cc = new CubieCube();

    private boolean isRec = false;

    /**
     *     Verbose_Mask determines if a " . " separates the phase1 and phase2 parts of the solver string like in F' R B R L2 F .
     *     U2 U D for example.<br>
     */
    public static final int USE_SEPARATOR = 0x1;

    /**
     *     Verbose_Mask determines if the solution will be inversed to a scramble/state generator.
     */
    public static final int INVERSE_SOLUTION = 0x2;

    /**
     *     Verbose_Mask determines if a tag such as "(21f)" will be appended to the solution.
     */
    public static final int APPEND_LENGTH = 0x4;

    /**
     *     Verbose_Mask determines if guaranteeing the solution to be optimal.
     */
    public static final int OPTIMAL_SOLUTION = 0x8;


    public Search() {
        for (int i = 0; i < 21; i++) {
            nodeUD[i] = EXTRA_PRUN_LEVEL > 0 ? new CoordCubeHuge() : new CoordCube();
            nodeRL[i] = EXTRA_PRUN_LEVEL > 0 ? new CoordCubeHuge() : new CoordCube();
            nodeFB[i] = EXTRA_PRUN_LEVEL > 0 ? new CoordCubeHuge() : new CoordCube();
        }
        for (int i = 0; i < 6; i++) {
            for (int j = 0; j < PRE_IDX_MAX; j++) {
                node0[i][j] = EXTRA_PRUN_LEVEL > 0 ? new CoordCubeHuge() : new CoordCube();
            }
        }
    }

    /**
     * Computes the solver string for a given cube.
     *
     * @param facelets
     *      is the cube definition string format.<br>
     * The names of the facelet positions of the cube:
     * <pre>
     *             |************|
     *             |*U1**U2**U3*|
     *             |************|
     *             |*U4**U5**U6*|
     *             |************|
     *             |*U7**U8**U9*|
     *             |************|
     * ************|************|************|************|
     * *L1**L2**L3*|*F1**F2**F3*|*R1**R2**F3*|*B1**B2**B3*|
     * ************|************|************|************|
     * *L4**L5**L6*|*F4**F5**F6*|*R4**R5**R6*|*B4**B5**B6*|
     * ************|************|************|************|
     * *L7**L8**L9*|*F7**F8**F9*|*R7**R8**R9*|*B7**B8**B9*|
     * ************|************|************|************|
     *             |************|
     *             |*D1**D2**D3*|
     *             |************|
     *             |*D4**D5**D6*|
     *             |************|
     *             |*D7**D8**D9*|
     *             |************|
     * </pre>
     * A cube definition string "UBL..." means for example: In position U1 we have the U-color, in position U2 we have the
     * B-color, in position U3 we have the L color etc. For example, the "super flip" state is represented as <br>
     * <pre>UBULURUFURURFRBRDRFUFLFRFDFDFDLDRDBDLULBLFLDLBUBRBLBDB</pre>
     * and the state generated by "F U' F2 D' B U R' F' L D' R' U' L U B' D2 R' F U2 D2" can be represented as <br>
     * <pre>FBLLURRFBUUFBRFDDFUULLFRDDLRFBLDRFBLUUBFLBDDBUURRBLDDR</pre>
     *
     * @param maxDepth
     *      defines the maximal allowed maneuver length. For random cubes, a maxDepth of 21 usually will return a
     *      solution in less than 0.02 seconds on average. With a maxDepth of 20 it takes about 0.1 seconds on average to find a
     *      solution, but it may take much longer for specific cubes.
     *
     * @param probeMax
     *      defines the maximum number of the probes of phase 2. If it does not return with a solution, it returns with
     *      an error code.
     *
     * @param probeMin
     *      defines the minimum number of the probes of phase 2. So, if a solution is found within given probes, the
     *      computing will continue to find shorter solution(s). Btw, if probeMin > probeMax, probeMin will be set to probeMax.
     *
     * @param verbose
     *      determins the format of the solution(s). see USE_SEPARATOR, INVERSE_SOLUTION, APPEND_LENGTH, OPTIMAL_SOLUTION
     *
     * @return The solution string or an error code:<br>
     *      Error 1: There is not exactly one facelet of each colour<br>
     *      Error 2: Not all 12 edges exist exactly once<br>
     *      Error 3: Flip error: One edge has to be flipped<br>
     *      Error 4: Not all corners exist exactly once<br>
     *      Error 5: Twist error: One corner has to be twisted<br>
     *      Error 6: Parity error: Two corners or two edges have to be exchanged<br>
     *      Error 7: No solution exists for the given maxDepth<br>
     *      Error 8: Probe limit exceeded, no solution within given probMax
     */
    public synchronized String solution(String facelets, int maxDepth, long probeMax, long probeMin, int verbose) {
        int check = verify(facelets);
        if (check != 0) {
            return "Error " + Math.abs(check);
        }
        this.sol = maxDepth + 1;
        this.probe = 0;
        this.probeMax = probeMax;
        this.probeMin = Math.min(probeMin, probeMax);
        this.verbose = verbose;
        this.solution = null;
        this.isRec = false;

        init();

        initSearch();

        return (verbose & OPTIMAL_SOLUTION) == 0 ? search() : searchopt();
    }

    private void initSearch() {
        conjMask = (TRY_INVERSE ? 0 : 0x38) | (TRY_THREE_AXES ? 0 : 0x36);
        CubieCube pc = new CubieCube();
        selfSym = cc.selfSymmetry();
        if (selfSym >> 48 != 0) {
            conjMask |= 0x38;
        }
        if ((selfSym >> 16 & 0xffff) != 0) {
            conjMask |= 0x12;
        }
        if ((selfSym >> 32 & 0xffff) != 0) {
            conjMask |= 0x24;
        }
        preIdxMax = conjMask > 7 ? 1 : PRE_IDX_MAX;
        for (int i = 0; i < 6; i++) {
            node0[i][0].set(cc);
            corn0[i][0] = cc.getCPermSym();
            ud8e0[i][0] = cc.getU4Comb() << 16 | cc.getD4Comb();
            if ((conjMask & 1 << i) == 0) {
                for (int j = 1; j < preIdxMax; j++) {
                    CubieCube.CornMult(CubieCube.moveCube[CubieCube.preMove[j]], cc, pc);
                    CubieCube.EdgeMult(CubieCube.moveCube[CubieCube.preMove[j]], cc, pc);
                    node0[i][j].set(pc);
                    corn0[i][j] = pc.getCPermSym();
                    ud8e0[i][j] = pc.getU4Comb() << 16 | pc.getD4Comb();
                }
            }
            cc.URFConjugate();
            if (i % 3 == 2) {
                cc.invCubieCube();
            }
        }
        selfSym = selfSym & 0xffffffffffffL;
    }

    public synchronized String next(long probeMax, long probeMin, int verbose) {
        this.probe = 0;
        this.probeMax = probeMax;
        this.probeMin = Math.min(probeMin, probeMax);
        this.solution = null;
        this.isRec = (this.verbose & OPTIMAL_SOLUTION) == (verbose & OPTIMAL_SOLUTION);
        this.verbose = verbose;
        return (verbose & OPTIMAL_SOLUTION) == 0 ? search() : searchopt();
    }

    public static boolean isInited() {
        return inited;
    }

    public long numberOfProbes() {
        return probe;
    }

    public int length() {
        return sol;
    }

    public synchronized static void init() {
        if (inited) {
            return;
        }
        CubieCube.initMove();
        CubieCube.initSym();

        if (EXTRA_PRUN_LEVEL > 0) {
            CoordCubeHuge.init();
        } else {
            CoordCube.init();
        }

        inited = true;
    }

    int verify(String facelets) {
        int count = 0x000000;
        try {
            String center = new String(
                new char[] {
                    facelets.charAt(Util.U5),
                    facelets.charAt(Util.R5),
                    facelets.charAt(Util.F5),
                    facelets.charAt(Util.D5),
                    facelets.charAt(Util.L5),
                    facelets.charAt(Util.B5)
                }
            );
            for (int i = 0; i < 54; i++) {
                f[i] = (byte) center.indexOf(facelets.charAt(i));
                if (f[i] == -1) {
                    return -1;
                }
                count += 1 << (f[i] << 2);
            }
        } catch (Exception e) {
            return -1;
        }
        if (count != 0x999999) {
            return -1;
        }
        Util.toCubieCube(f, cc);
        return cc.verify();
    }

    private String search() {
        for (length1 = isRec ? length1 : 0; length1 < sol; length1++) {
            maxDep2 = Math.min(MAX_DEPTH2, sol - length1);
            for (urfIdx = isRec ? urfIdx : 0; urfIdx < 6; urfIdx++) {
                if ((conjMask & 1 << urfIdx) != 0) {
                    continue;
                }
                for (preIdx = isRec ? preIdx : 0; preIdx < preIdxMax; preIdx++) {
                    if (preIdx != 0 && preIdx % 2 == 0) {
                        continue;
                    }
                    node0[urfIdx][preIdx].calcPruning(true);
                    int ssym = (int) (0xffff & selfSym);
                    if (preIdx != 0) {
                        ssym &= CubieCube.moveCubeSym[CubieCube.preMove[preIdx]];
                    }
                    depth1 = length1 - (preIdx == 0 ? 0 : 1);
                    if (node0[urfIdx][preIdx].prun <= depth1
                            && phase1(node0[urfIdx][preIdx], ssym, depth1, -1) == 0) {
                        return solution == null ? "Error 8" : solution;
                    }
                }
            }
        }
        return solution == null ? "Error 7" : solution;
    }

    /**
     * @return
     *      0: Found or Probe limit exceeded
     *      1: Try Next Power
     *      2: Try Next Axis
     */
    private int phase1(CoordCube node, long ssym, int maxl, int lm) {
        if (node.prun == 0 && maxl < 5) {
            if (maxl == 0) {
                int ret = initPhase2();
                if (ret == 0 || preIdx == 0) {
                    return ret;
                }
                preIdx++;
                ret = Math.min(initPhase2(), ret);
                preIdx--;
                return ret;
            } else {
                return 1;
            }
        }

        int skipMoves = 0;
        int i = 1;
        for (long s = ssym; (s >>= 1) != 0; i++) {
            if ((s & 1) == 1) {
                skipMoves |= CubieCube.firstMoveSym[i];
            }
        }

        for (int axis = 0; axis < 18; axis += 3) {
            if (axis == lm || axis == lm - 9
                    || (isRec && axis < move[depth1 - maxl] - 2)) {
                continue;
            }
            for (int power = 0; power < 3; power++) {
                int m = axis + power;

                if (isRec && m != move[depth1 - maxl]
                        || ssym != 1 && (skipMoves & 1 << m) != 0) {
                    continue;
                }

                int prun = nodeUD[maxl].doMovePrun(node, m, true);
                if (prun > maxl) {
                    break;
                } else if (prun == maxl) {
                    continue;
                }

                move[depth1 - maxl] = m;
                int ret = phase1(nodeUD[maxl], ssym & CubieCube.moveCubeSym[m], maxl - 1, axis);
                if (ret == 0) {
                    return 0;
                } else if (ret == 2) {
                    break;
                }
            }
        }
        return 1;
    }

    private String searchopt() {
        int maxprun1 = 0;
        int maxprun2 = 0;
        for (int i = 0; i < 6; i++) {
            node0[i][0].calcPruning(false);
            if (i < 3) {
                maxprun1 = Math.max(maxprun1, node0[i][0].prun);
            } else {
                maxprun2 = Math.max(maxprun2, node0[i][0].prun);
            }
        }
        urfIdx = maxprun2 > maxprun1 ? 3 : 0;
        preIdx = 0;
        for (length1 = isRec ? length1 : 0; length1 < sol; length1++) {
            CoordCube ud = node0[0 + urfIdx][0];
            CoordCube rl = node0[1 + urfIdx][0];
            CoordCube fb = node0[2 + urfIdx][0];

            if (ud.prun <= length1 && rl.prun <= length1 && fb.prun <= length1
                    && phase1opt(ud, rl, fb, selfSym, length1, -1) == 0) {
                return solution == null ? "Error 8" : solution;
            }
        }
        return solution == null ? "Error 7" : solution;
    }

    /**
     * @return
     *      0: Found or Probe limit exceeded
     *      1: Try Next Power
     *      2: Try Next Axis
     */
    private int phase1opt(CoordCube ud, CoordCube rl, CoordCube fb, long ssym, int maxl, int lm) {
        if (ud.prun == 0 && rl.prun == 0 && fb.prun == 0 && maxl < 5) {
            maxDep2 = maxl + 1;
            depth1 = length1 - maxl;
            return initPhase2() == 0 ? 0 : 1;
        }

        int skipMoves = 0;
        int i = 1;
        for (long s = ssym; (s >>= 1) != 0; i++) {
            if ((s & 1) == 1) {
                skipMoves |= CubieCube.firstMoveSym[i];
            }
        }

        for (int axis = 0; axis < 18; axis += 3) {
            if (axis == lm || axis == lm - 9 || (isRec && axis < move[length1 - maxl] - 2)) {
                continue;
            }
            for (int power = 0; power < 3; power++) {
                int m = axis + power;

                if (isRec && m != move[length1 - maxl]
                        || ssym != 1 && (skipMoves & 1 << m) != 0) {
                    continue;
                }

                // UD Axis
                int prun_ud = nodeUD[maxl].doMovePrun(ud, m, false);
                if (prun_ud > maxl) {
                    break;
                } else if (prun_ud == maxl) {
                    continue;
                }

                // RL Axis
                m = CubieCube.urfMove[2][m];

                int prun_rl = nodeRL[maxl].doMovePrun(rl, m, false);
                if (prun_rl > maxl) {
                    break;
                } else if (prun_rl == maxl) {
                    continue;
                }

                // FB Axis
                m = CubieCube.urfMove[2][m];

                int prun_fb = nodeFB[maxl].doMovePrun(fb, m, false);
                if (prun_ud == prun_rl && prun_rl == prun_fb && prun_fb != 0) {
                    prun_fb++;
                }

                if (prun_fb > maxl) {
                    break;
                } else if (prun_fb == maxl) {
                    continue;
                }

                m = CubieCube.urfMove[2][m];

                move[length1 - maxl] = m;
                int ret = phase1opt(nodeUD[maxl], nodeRL[maxl], nodeFB[maxl], ssym & CubieCube.moveCubeSym[m], maxl - 1, axis);
                if (ret == 0) {
                    return 0;
                } else if (ret == 2) {
                    break;
                }
            }
        }
        return 1;
    }

    /**
     * @return
     *      0: Found or Probe limit exceeded
     *      1: Try Next Power
     *      2: Try Next Axis
     */
    private int initPhase2() {
        isRec = false;
        if (probe >= (solution == null ? probeMax : probeMin)) {
            return 0;
        }
        ++probe;
        int cidx = corn0[urfIdx][preIdx] >> 4;
        int csym = corn0[urfIdx][preIdx] & 0xf;
        int mid = node0[urfIdx][preIdx].slice;
        for (int i = 0; i < depth1; i++) {
            int m = move[i];
            cidx = CoordCube.CPermMove[cidx][CubieCube.SymMove[csym][m]];
            csym = CubieCube.SymMult[cidx & 0xf][csym];
            cidx >>= 4;

            int cx = CoordCube.UDSliceMove[mid & 0x1ff][m];
            mid = Util.permMult[mid >> 9][cx >> 9] << 9 | cx & 0x1ff;
        }
        mid >>= 9;
        int prun = CoordCube.getPruning(CoordCube.MCPermPrun, cidx * 24 + CoordCube.MPermConj[mid][csym]);
        if (prun >= maxDep2) {
            return prun > maxDep2 ? 2 : 1;
        }

        int u4e = ud8e0[urfIdx][preIdx] >> 16;
        int d4e = ud8e0[urfIdx][preIdx] & 0xffff;
        for (int i = 0; i < depth1; i++) {
            int m = move[i];

            int cx = CoordCube.UDSliceMove[u4e & 0x1ff][m];
            u4e = Util.permMult[u4e >> 9][cx >> 9] << 9 | cx & 0x1ff;

            cx = CoordCube.UDSliceMove[d4e & 0x1ff][m];
            d4e = Util.permMult[d4e >> 9][cx >> 9] << 9 | cx & 0x1ff;
        }

        int edge = CubieCube.MtoEPerm[494 - (u4e & 0x1ff) + (u4e >> 9) * 70 + (d4e >> 9) * 1680];
        int esym = edge & 0xf;
        edge >>= 4;

        prun = Math.max(prun, Math.max(
                            CoordCube.getPruning(CoordCube.MEPermPrun,
                                    edge * 24 + CoordCube.MPermConj[mid][esym]),
                            CoordCube.getPruning(CoordCube.EPermCCombPrun,
                                    edge * 70 + CoordCube.CCombConj[CubieCube.Perm2Comb[cidx]][CubieCube.SymMultInv[esym][csym]])));

        if (prun >= maxDep2) {
            return prun > maxDep2 ? 2 : 1;
        }

        int lm = 10;
        if (depth1 >= 2 && move[depth1 - 1] / 3 % 3 == move[depth1 - 2] / 3 % 3) {
            lm = Util.std2ud[Math.max(move[depth1 - 1], move[depth1 - 2]) / 3 * 3 + 1];
        } else if (depth1 >= 1) {
            lm = Util.std2ud[move[depth1 - 1] / 3 * 3 + 1];
            if (move[depth1 - 1] > Util.Fx3) {
                lm = -lm;
            }
        }

        int depth2;
        for (depth2 = maxDep2 - 1; depth2 >= prun; depth2--) {
            int ret = phase2(edge, esym, cidx, csym, mid, depth2, depth1, lm);
            if (ret < 0) {
                break;
            }
            depth2 = depth2 - ret;
            sol = depth1 + depth2;
            if (preIdx != 0) {
                assert depth2 > 0; //If depth2 == 0, the solution is optimal. In this case, we won't try preScramble to find shorter solutions.
                int axisPre = Util.preMove[preIdx] / 3;
                int axisLast = move[sol - 1] / 3;
                if (axisPre == axisLast) {
                    int pow = (Util.preMove[preIdx] % 3 + move[sol - 1] % 3 + 1) % 4;
                    move[sol - 1] = axisPre * 3 + pow;
                } else if (depth2 > 1
                           && axisPre % 3 == axisLast % 3
                           && move[sol - 2] / 3 == axisPre) {
                    int pow = (Util.preMove[preIdx] % 3 + move[sol - 2] % 3 + 1) % 4;
                    move[sol - 2] = axisPre * 3 + pow;
                } else {
                    move[sol++] = Util.preMove[preIdx];
                }
            }
            solution = solutionToString();
        }

        if (depth2 != maxDep2 - 1) { //At least one solution has been found.
            maxDep2 = Math.min(MAX_DEPTH2, sol - length1);
            return probe >= probeMin ? 0 : 1;
        } else {
            return 1;
        }
    }

    //-1: no solution found
    // X: solution with X moves shorter than expectation. Hence, the length of the solution is  depth - X
    private int phase2(int eidx, int esym, int cidx, int csym, int mid, int maxl, int depth, int lm) {
        if (eidx == 0 && cidx == 0 && mid == 0) {
            return maxl;
        }
        for (int m = 0; m < 10; m++) {
            if (lm < 0 ? (m == -lm) : Util.ckmv2[lm][m]) {
                continue;
            }
            int midx = CoordCube.MPermMove[mid][m];
            int cidxx = CoordCube.CPermMove[cidx][CubieCube.SymMove[csym][Util.ud2std[m]]];
            int csymx = CubieCube.SymMult[cidxx & 0xf][csym];
            cidxx >>= 4;
            if (CoordCube.getPruning(CoordCube.MCPermPrun,
                                     cidxx * 24 + CoordCube.MPermConj[midx][csymx]) >= maxl) {
                continue;
            }
            int eidxx = CoordCube.EPermMove[eidx][CubieCube.SymMoveUD[esym][m]];
            int esymx = CubieCube.SymMult[eidxx & 0xf][esym];
            eidxx >>= 4;
            if (CoordCube.getPruning(CoordCube.EPermCCombPrun,
                                     eidxx * 70 + CoordCube.CCombConj[CubieCube.Perm2Comb[cidxx]][CubieCube.SymMultInv[esymx][csymx]]) >= maxl) {
                continue;
            }
            if (CoordCube.getPruning(CoordCube.MEPermPrun,
                                     eidxx * 24 + CoordCube.MPermConj[midx][esymx]) >= maxl) {
                continue;
            }
            int ret = phase2(eidxx, esymx, cidxx, csymx, midx, maxl - 1, depth + 1, (lm < 0 && m + lm == -5) ? -lm : m);
            if (ret >= 0) {
                move[depth] = Util.ud2std[m];
                return ret;
            }
        }
        return -1;
    }

    private String solutionToString() {
        StringBuffer sb = new StringBuffer();
        int urf = (verbose & INVERSE_SOLUTION) != 0 ? (urfIdx + 3) % 6 : urfIdx;
        if (urf < 3) {
            for (int s = 0; s < sol; s++) {
                if ((verbose & USE_SEPARATOR) != 0 && s == depth1) {
                    sb.append(".  ");
                }
                sb.append(Util.move2str[CubieCube.urfMove[urf][move[s]]]).append(' ');
            }
        } else {
            for (int s = sol - 1; s >= 0; s--) {
                sb.append(Util.move2str[CubieCube.urfMove[urf][move[s]]]).append(' ');
                if ((verbose & USE_SEPARATOR) != 0 && s == depth1) {
                    sb.append(".  ");
                }
            }
        }
        if ((verbose & APPEND_LENGTH) != 0) {
            sb.append("(").append(sol).append("f)");
        }
        return sb.toString();
    }
}
