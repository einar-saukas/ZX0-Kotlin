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

class Compressor {
    private var output: ByteArray = ByteArray(0)
    private var outputIndex = 0
    private var inputIndex = 0
    private var bitIndex = 0
    private var bitMask = 0
    private var diff = 0
    private var delta = 0
    private var backtrack = true

    private fun readBytes(n: Int) {
        inputIndex += n
        diff += n
        if (delta < diff) {
            delta = diff
        }
    }

    private fun writeByte(value: Int) {
        output[outputIndex++] = value.toByte()
        diff--
    }

    private fun writeBit(value: Int) {
        if (backtrack) {
            if (value > 0) {
                output[outputIndex - 1] = (output[outputIndex - 1] + 1).toByte()
            }
            backtrack = false
        } else {
            if (bitMask == 0) {
                bitMask = 128
                bitIndex = outputIndex
                writeByte(0)
            }
            if (value > 0) {
                output[bitIndex] = (output[bitIndex] + bitMask).toByte()
            }
            bitMask = bitMask shr 1
        }
    }

    private fun writeInterlacedEliasGamma(value: Int, backwardsMode: Boolean, invertMode: Boolean) {
        var i = 2
        while (i <= value) {
            i = i shl 1
        }
        i = i shr 2
        while (i > 0) {
            writeBit(if (backwardsMode) 1 else 0)
            writeBit(if (invertMode == (value and i == 0)) 1 else 0)
            i = i shr 1
        }
        writeBit(if (!backwardsMode) 1 else 0)
    }

    fun compress(optimal: Block, input: ByteArray, skip: Int, backwardsMode: Boolean, invertMode: Boolean): Pair<ByteArray, Int> {
        var lastOffset = INITIAL_OFFSET

        // calculate and allocate output buffer
        output = ByteArray((optimal.bits + 25) / 8)

        // un-reverse optimal sequence
        var current: Block? = optimal
        var prev: Block? = null
        while (current != null) {
            val next = current.chain
            current.chain = prev
            prev = current
            current = next
        }

        // initialize data
        diff = output.size - input.size + skip
        delta = 0
        inputIndex = skip
        outputIndex = 0
        bitMask = 0
        backtrack = true

        // generate output
        current = prev!!.chain
        while (current != null) {
            val length = current.index - prev!!.index
            when (current.offset) {
                0 -> {
                    // copy literals indicator
                    writeBit(0)

                    // copy literals length
                    writeInterlacedEliasGamma(length, backwardsMode, false)

                    // copy literals values
                    for (i in 0 until length) {
                        writeByte(input[inputIndex].toInt())
                        readBytes(1)
                    }
                }
                lastOffset -> {
                    // copy from last offset indicator
                    writeBit(0)

                    // copy from last offset length
                    writeInterlacedEliasGamma(length, backwardsMode, false)
                    readBytes(length)
                }
                else -> {
                    // copy from new offset indicator
                    writeBit(1)

                    // copy from new offset MSB
                    writeInterlacedEliasGamma((current.offset - 1) / 128 + 1, backwardsMode, invertMode)

                    // copy from new offset LSB
                    writeByte((if (backwardsMode) (current.offset - 1) % 128 else 127 - ((current.offset - 1) % 128))*2)

                    // copy from new offset length
                    backtrack = true
                    writeInterlacedEliasGamma(length - 1, backwardsMode, false)
                    readBytes(length)

                    lastOffset = current.offset
                }
            }
            prev = current
            current = current.chain
        }

        // end marker
        writeBit(1)
        writeInterlacedEliasGamma(256, backwardsMode, invertMode)
        return Pair(output, delta)
    }
}