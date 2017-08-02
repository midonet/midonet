/*
 * Copyright (c) 2016 Midokura SARL
 */

package org.midonet.util.io

import java.io.File
import java.nio.file.FileAlreadyExistsException

import scala.util.Random

import org.apache.commons.io.{FileUtils, FilenameUtils}

/**
  * A class that generates a random file at the specified location with the
  * specified length.
  *
  * Used for testing when we need some file to access.
  *
  * @param path Desired path of the random file.
  * @param length Length in bytes of the file.
  * @throws FileAlreadyExistsException If the a file already exists in the path.
  */
class RandomFile(path: String, length: Int) extends File(path) {
    // If file already exists
    if (exists) {
        throw new FileAlreadyExistsException(path)
    }

    // Generate random content of desired length
    val contentBytes = Array.ofDim[Byte](length)
    val random = new Random
    random.nextBytes(contentBytes)

    // Write to file creating directory structures if needed
    FileUtils.writeByteArrayToFile(this, contentBytes)
}

object RandomFile {
    private val DEFAULT_LENGTH = 20

    def apply(basePath: String, relativePath: String = "",
              length: Int = DEFAULT_LENGTH): RandomFile = {
        val pathWithNoPrefix =
            relativePath.substring(FilenameUtils.getPrefixLength(relativePath))
        val absolutePath = FilenameUtils.normalize(
            FilenameUtils.concat(basePath, pathWithNoPrefix)
        )
        new RandomFile(absolutePath, length)
    }
}
