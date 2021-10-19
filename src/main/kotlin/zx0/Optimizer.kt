/*
 * (c) Copyright 2021 by Einar Saukas. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * The name of its author may not be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package zx0

import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.*

const val INITIAL_OFFSET = 1
const val MAX_SCALE = 50

private fun offsetCeiling(index: Int, offsetLimit: Int): Int = min(max(index, INITIAL_OFFSET), offsetLimit)

private fun eliasGammaBits(value: Int): Int {
    var i = value
    var bits = 1
    while (i > 1) {
        bits += 2
        i = i shr 1
    }
    return bits
}

class Optimizer {
    private var lastLiteral = arrayOfNulls<Block>(0)
    private var lastMatch = arrayOfNulls<Block>(0)
    private var optimal = Array(0) { Block() }
    private var matchLength = IntArray(0)

    fun optimize(input: ByteArray, skip: Int, offsetLimit: Int, threads: Int, verbose: Boolean): Block {

        // allocate all main data structures at once
        val arraySize = offsetCeiling(input.size - 1, offsetLimit) + 1
        lastLiteral = arrayOfNulls(arraySize)
        lastMatch = arrayOfNulls(arraySize)
        optimal = Array(input.size) { Block() }
        matchLength = IntArray(arraySize)

        // start with fake block
        lastMatch[INITIAL_OFFSET] = Block(-1, skip - 1, INITIAL_OFFSET)

        var dots = 2
        if (verbose) {
            print("[")
        }

        // process remaining bytes
        val pool = if (threads > 1) Executors.newFixedThreadPool(threads) else null
        for (index in skip until input.size) {
            val maxOffset = offsetCeiling(index, offsetLimit)
            if (pool == null) {
                optimal[index] = processTask(1, maxOffset, index, skip, input)
            } else {
                val taskSize = maxOffset / threads + 1
                val tasks = LinkedList<Future<Block>>()
                for (firstOffset in 1..maxOffset step taskSize) {
                    val lastOffset = min(firstOffset + taskSize - 1, maxOffset)
                    tasks.add(pool.submit<Block> { processTask(firstOffset, lastOffset, index, skip, input) })
                }
                for (task in tasks) {
                    val taskBlock = task.get()
                    if (optimal[index].bits > taskBlock.bits) {
                        optimal[index] = taskBlock
                    }
                }
            }

            // indicate progress
            if (verbose && index * MAX_SCALE / input.size > dots) {
                print(".")
                dots++
            }
        }
        pool?.shutdown()

        if (verbose) {
            println("]")
        }

        return optimal[input.size - 1]
    }

    private fun processTask(firstOffset: Int, lastOffset: Int, index: Int, skip: Int, input: ByteArray): Block {
        val bestLength = IntArray(input.size + 1)
        bestLength[2] = 2
        var bestLengthSize = 2
        var optimalBlock = Block()
        for (offset in firstOffset..lastOffset) {
            if (index != skip && index >= offset && input[index] == input[index - offset]) {
                // copy from last offset
                val lt = lastLiteral[offset]
                if (lt != null) {
                    val length = index- lt.index
                    val bits = lt.bits + 1 + eliasGammaBits(length)
                    val mt = Block(bits, index, offset, lt)
                    lastMatch[offset] = mt
                    if (optimalBlock.bits > bits) {
                        optimalBlock = mt
                    }
                }
                // copy from new offset
                if (++matchLength[offset] > 1) {
                    if (bestLengthSize < matchLength[offset]) {
                        var bits = optimal[index-bestLength[bestLengthSize]].bits + eliasGammaBits(bestLength[bestLengthSize]-1)
                        do {
                            bestLengthSize++
                            val bits2 = optimal[index-bestLengthSize].bits + eliasGammaBits(bestLengthSize-1)
                            if (bits2 <= bits) {
                                bestLength[bestLengthSize] = bestLengthSize
                                bits = bits2
                            } else {
                                bestLength[bestLengthSize] = bestLength[bestLengthSize-1]
                            }
                        } while(bestLengthSize < matchLength[offset])
                    }
                    val length = bestLength[matchLength[offset]]
                    val bits = optimal[index-length].bits + 8 + eliasGammaBits((offset-1)/128+1) + eliasGammaBits(length-1)
                    var mt = lastMatch[offset]
                    if (mt == null || mt.index != index || mt.bits > bits) {
                        mt = Block(bits, index, offset, optimal[index-length])
                        lastMatch[offset] = mt
                        if (optimalBlock.bits > bits) {
                            optimalBlock = mt
                        }
                    }
                }
            } else {
                // copy literals
                matchLength[offset] = 0
                val mt = lastMatch[offset]
                if (mt != null) {
                    val length = index - mt.index
                    val bits = mt.bits + 1 + eliasGammaBits(length) + length*8
                    val lt = Block(bits, index, 0, mt)
                    lastLiteral[offset] = lt
                    if (optimalBlock.bits > bits) {
                        optimalBlock = lt
                    }
                }
            }
        }
        return optimalBlock
    }
}