/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.extension.ftp.internal;

import static java.util.Optional.ofNullable;
import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;
import static org.slf4j.LoggerFactory.getLogger;

import org.mule.extension.file.common.api.AbstractFileInputStreamSupplier;
import org.mule.extension.file.common.api.FileAttributes;
import org.mule.extension.file.common.api.lock.PathLock;
import org.mule.extension.file.common.api.stream.AbstractFileInputStream;
import org.mule.extension.file.common.api.stream.LazyStreamSupplier;
import org.mule.extension.ftp.api.ftp.FtpFileAttributes;
import org.mule.extension.ftp.internal.connection.FtpFileSystem;
import org.mule.runtime.api.connection.ConnectionException;
import org.mule.runtime.api.connection.ConnectionHandler;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.core.api.connector.ConnectionManager;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

import org.slf4j.Logger;

/**
 * An {@link AbstractFileInputStream} implementation which obtains a {@link FtpFileSystem} through a {@link ConnectionManager} and
 * uses it to obtain the contents of a file on a FTP server.
 * <p>
 * When the stream is closed or fully consumed, the {@link FtpFileSystem} is released back to the {@link ConnectionManager}
 *
 * @since 1.0
 */
public abstract class FtpInputStream extends AbstractFileInputStream {

  protected FtpFileInputStreamSupplier ftpFileInputStreamSupplier;

  protected static ConnectionManager getConnectionManager(FtpConnector config) throws ConnectionException {
    return config.getConnectionManager();
  }

  protected FtpInputStream(FtpFileInputStreamSupplier ftpFileInputStreamSupplier, PathLock lock) throws ConnectionException {
    super(new LazyStreamSupplier(ftpFileInputStreamSupplier), lock);
    this.ftpFileInputStreamSupplier = ftpFileInputStreamSupplier;
  }

  @Override
  protected void doClose() throws IOException {
    try {
      super.doClose();
    } finally {
      try {
        beforeConnectionRelease();
      } finally {
        ftpFileInputStreamSupplier.getConnectionHandler().ifPresent(ConnectionHandler::release);
      }
    }
  }

  /**
   * Template method for performing operations just after the stream is closed but before the connection is released. This default
   * implementation is empty.
   *
   * @throws IOException
   */
  protected void beforeConnectionRelease() throws IOException {}

  /**
   * @return {@link Optional} of the {@link FtpFileSystem} used to obtain the stream
   */
  protected Optional<FtpFileSystem> getFtpFileSystem() {
    return ftpFileInputStreamSupplier.getFtpFileSystem();
  }

  protected static class FtpFileInputStreamSupplier extends AbstractFileInputStreamSupplier {

    private static final Logger LOGGER = getLogger(AbstractFileInputStreamSupplier.class);

    private ConnectionHandler<FtpFileSystem> connectionHandler;
    private ConnectionManager connectionManager;
    private FtpFileSystem ftpFileSystem;
    private FtpConnector config;

    FtpFileInputStreamSupplier(FtpFileAttributes attributes, ConnectionManager connectionManager,
                               Long timeBetweenSizeCheck, FtpConnector config) {
      super(attributes, timeBetweenSizeCheck);
      this.connectionManager = connectionManager;
      this.config = config;
    }

    @Override
    protected FileAttributes getUpdatedAttributes() {
      try {
        ConnectionHandler<FtpFileSystem> connectionHandler = connectionManager.getConnection(config);
        FtpFileSystem ftpFileSystem = connectionHandler.getConnection();
        FtpFileAttributes updatedFtpFileAttributes = ftpFileSystem.getFileAttributes(attributes.getPath());
        connectionHandler.release();
        if (updatedFtpFileAttributes == null) {
          LOGGER.error(String.format(FILE_NO_LONGER_EXISTS_MESSAGE, attributes.getPath()));
        }
        return updatedFtpFileAttributes;
      } catch (ConnectionException e) {
        throw new MuleRuntimeException(createStaticMessage("Could not obtain connection to fetch file " + attributes.getPath()),
                                       e);
      }
    }

    @Override
    protected InputStream getContentInputStream() {
      try {
        connectionHandler = connectionManager.getConnection(config);
        ftpFileSystem = connectionHandler.getConnection();
        return ftpFileSystem.retrieveFileContent(attributes);
      } catch (MuleRuntimeException e) {
        if (e.getCause() instanceof FileNotFoundException) {
          onFileDeleted(e);
        }
        throw e;
      } catch (ConnectionException e) {
        throw new MuleRuntimeException(createStaticMessage("Could not obtain connection to fetch file " + attributes.getPath()),
                                       e);
      }
    }

    public Optional<ConnectionHandler> getConnectionHandler() {
      return ofNullable(connectionHandler);
    }

    public Optional<FtpFileSystem> getFtpFileSystem() {
      return ofNullable(ftpFileSystem);
    }

  }
}
