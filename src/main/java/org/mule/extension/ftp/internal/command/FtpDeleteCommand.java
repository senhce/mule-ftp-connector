/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.extension.ftp.internal.command;

import static java.lang.String.format;
import static org.mule.extension.ftp.internal.FtpUtils.normalizePath;
import static org.slf4j.LoggerFactory.getLogger;
import org.mule.extension.file.common.api.FileAttributes;
import org.mule.extension.file.common.api.command.DeleteCommand;
import org.mule.extension.ftp.api.ftp.FtpFileAttributes;
import org.mule.extension.ftp.internal.connection.FtpFileSystem;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.slf4j.Logger;

public final class FtpDeleteCommand extends FtpCommand implements DeleteCommand {

  private static Logger LOGGER = getLogger(FtpDeleteCommand.class);

  /**
   * {@inheritDoc}
   */
  public FtpDeleteCommand(FtpFileSystem fileSystem, FTPClient client) {
    super(fileSystem, client);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void delete(String filePath) {
    FileAttributes fileAttributes = getExistingFile(filePath);
    boolean isDirectory = fileAttributes.isDirectory();
    Path path = Paths.get(fileAttributes.getPath());

    if (isDirectory) {
      LOGGER.debug("Preparing to delete directory '{}'", path);
      deleteDirectory(path);
    } else {
      deleteFile(path);
    }
  }

  private void deleteFile(Path path) {
    fileSystem.verifyNotLocked(path);
    try {
      if (!client.deleteFile(normalizePath(path))) {
        throw exception("Could not delete file " + path);
      }
    } catch (Exception e) {
      throw exception("Found Exception while deleting directory " + path, e);
    }
    logDelete(path);
  }

  private void deleteDirectory(Path path) {
    changeWorkingDirectory(path);
    FTPFile[] files;
    try {
      files = client.listFiles();
    } catch (IOException e) {
      throw exception(format("Could not list contents of directory '%s' while trying to delete it", path), e);
    }

    for (FTPFile file : files) {
      if (isVirtualDirectory(file.getName())) {
        continue;
      }

      FileAttributes fileAttributes = new FtpFileAttributes(path.resolve(file.getName()), file);

      final Path filePath = Paths.get(fileAttributes.getPath());
      if (fileAttributes.isDirectory()) {
        deleteDirectory(filePath);
      } else {
        deleteFile(filePath);
      }
    }

    boolean removed;
    try {
      client.changeToParentDirectory();
      removed = client.removeDirectory(path.toString());
    } catch (IOException e) {
      throw exception("Found exception while trying to remove directory " + path, e);
    }

    if (!removed) {
      throw exception("Could not remove directory " + path);
    }

    logDelete(path);
  }

  private void logDelete(Path path) {
    LOGGER.debug("Successfully deleted '{}'", path);
  }
}
