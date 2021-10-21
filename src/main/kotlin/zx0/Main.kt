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

import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption.*
import kotlin.system.exitProcess

const val MAX_OFFSET_ZX0 = 32640
const val MAX_OFFSET_ZX7 = 2176
const val DEFAULT_THREADS = 4

private fun parseInt(s: String) = try { s.toInt() } catch (e: Exception) { -1 }

fun zx0(input: ByteArray, skip: Int, backwardsMode: Boolean, classicMode: Boolean, quickMode: Boolean, threads: Int, verbose: Boolean) =
    Compressor().compress(
        Optimizer().optimize(input, skip, if (quickMode) MAX_OFFSET_ZX7 else MAX_OFFSET_ZX0, threads, verbose),
        input, skip, backwardsMode, !classicMode && !backwardsMode)

fun dzx0(input: ByteArray, backwardsMode: Boolean, classicMode: Boolean) =
    Decompressor().decompress(input, backwardsMode, !classicMode && !backwardsMode)

fun main(args: Array<String>) {

    println("ZX0 v2.1: Optimal data compressor by Einar Saukas")

    // process optional parameters
    var threads = DEFAULT_THREADS
    var forcedMode = false
    var classicMode = false
    var backwardsMode = false
    var quickMode = false
    var decompress = false
    var skip = 0
    var i = 0
    while (i < args.size && (args[i].startsWith("-") || args[i].startsWith("+"))) {
        if (args[i].startsWith("-p")) {
            threads = parseInt(args[i].substring(2))
            if (threads <= 0) {
                System.err.println("Error: Invalid parameter " + args[i])
                exitProcess(1)
            }
        } else when (args[i]) {
            "-f" -> forcedMode = true
            "-c" -> classicMode = true
            "-b" -> backwardsMode = true
            "-q" -> quickMode = true
            "-d" -> decompress = true
            else -> {
                skip = parseInt(args[i])
                if (skip <= 0) {
                    System.err.println("Error: Invalid parameter " + args[i])
                    exitProcess(1)
                }
            }
        }
        i++
    }

    if (decompress && skip > 0) {
        System.err.println("Error: Decompressing with "+(if (backwardsMode) "suffix" else "prefix")+" not supported")
        exitProcess(1)
    }

    // determine output filename
    val outputName = when (args.size) {
        i + 1 -> {
            if (!decompress) {
                args[i] + ".zx0"
            } else {
                if (args[i].endsWith(".zx0")) {
                    args[i].removeSuffix(".zx0")
                } else {
                    System.err.println("Error: Cannot infer output filename")
                    exitProcess(1)
                }
            }
        }
        i + 2 -> args[i + 1]
        else -> {
            System.err.println("""Usage: java -jar zx0.jar [-pN] [-f] [-c] [-b] [-q] [-d] input [output.zx0]
  -p      Parallel processing with N threads
  -f      Force overwrite of output file
  -c      Classic file format (v1.*)
  -b      Compress backwards
  -q      Quick non-optimal compression
  -d      Decompress""")
            exitProcess(1)
        }
    }

    // read input file
    val input = try {
        Files.readAllBytes(Paths.get(args[i]))
    } catch (e: Exception) {
        System.err.println("Error: Cannot read input file " + args[i])
        exitProcess(1)
    }

    // determine input size
    if (input.isEmpty()) {
        System.err.println("Error: Empty input file " + args[i])
        exitProcess(1)
    }

    // validate skip against input size
    if (skip >= input.size) {
        System.err.println("Error: Skipping entire input file " + args[i])
        exitProcess(1)
    }

    // check output file
    if (!forcedMode && Files.exists(Paths.get(outputName))) {
        System.err.println("Error: Already existing output file $outputName")
        exitProcess(1)
    }

    // conditionally reverse input file
    if (backwardsMode) {
        input.reverse()
    }

    // generate output file
    val (output, delta) =
        if (!decompress) {
            zx0(input, skip, backwardsMode, classicMode, quickMode, threads, true)
        } else
            try {
                Pair(dzx0(input, backwardsMode, classicMode), 0)
            } catch (e: ArrayIndexOutOfBoundsException) {
                System.err.println("Error: Invalid input file " + args[i])
                exitProcess(1)
            }

    // conditionally reverse output file
    if (backwardsMode) {
        output.reverse()
    }

    // write output file
    try {
        Files.write(Paths.get(outputName), output, CREATE, if (forcedMode) TRUNCATE_EXISTING else CREATE_NEW)
    } catch (e: Exception) {
        System.err.println("Error: Cannot write output file $outputName")
        exitProcess(1)
    }

    // done!
    if (!decompress) {
        println("File " + (if (skip != 0) "partially " else "") + "compressed " + (if (backwardsMode) "backwards " else "") + "from " + (input.size - skip) + " to " + output.size + " bytes! (delta " + delta + ")")
    } else {
        println("File decompressed " + (if (backwardsMode) "backwards " else "") + "from " + (input.size - skip) + " to " + output.size + " bytes!")
    }
}
