/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.extension.ftp.internal.connection;

import static java.lang.String.format;
import static org.mule.extension.file.common.api.exceptions.FileError.CANNOT_REACH;
import static org.mule.extension.file.common.api.exceptions.FileError.CONNECTION_TIMEOUT;
import static org.mule.extension.file.common.api.exceptions.FileError.CONNECTIVITY;
import static org.mule.extension.file.common.api.exceptions.FileError.INVALID_CREDENTIALS;
import static org.mule.extension.file.common.api.exceptions.FileError.SERVICE_NOT_AVAILABLE;
import static org.mule.extension.file.common.api.exceptions.FileError.UNKNOWN_HOST;
import static org.mule.runtime.extension.api.annotation.param.ParameterGroup.CONNECTION;
import org.mule.extension.file.common.api.FileSystemProvider;
import org.mule.extension.file.common.api.exceptions.FileError;
import org.mule.extension.ftp.api.FTPConnectionException;
import org.mule.extension.ftp.api.ftp.FtpTransferMode;
import org.mule.extension.ftp.internal.TimeoutSettings;
import org.mule.runtime.api.connection.ConnectionException;
import org.mule.runtime.api.connection.ConnectionValidationResult;
import org.mule.runtime.api.connection.PoolingConnectionProvider;
import org.mule.runtime.api.lock.LockFactory;
import org.mule.runtime.extension.api.annotation.param.Optional;
import org.mule.runtime.extension.api.annotation.param.Parameter;
import org.mule.runtime.extension.api.annotation.param.ParameterGroup;
import org.mule.runtime.extension.api.annotation.param.display.DisplayName;
import org.mule.runtime.extension.api.annotation.param.display.Summary;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Connects to an FTP server
 *
 * @since 1.0
 */
@DisplayName("FTP Connection")
@Summary("Connection to connect against an FTP server")
public class FtpConnectionProvider extends FileSystemProvider<FtpFileSystem> implements
    PoolingConnectionProvider<FtpFileSystem> {

  private static final Logger LOGGER = LoggerFactory.getLogger(FtpConnectionProvider.class);
  private static final String FTP_ERROR_MESSAGE_MASK =
      "Could not establish FTP connection with host: '%s' at port: '%d' - %s";
  public static final String ERROR_CODE_MASK = "Error code: %d - %s";

  @Inject
  private LockFactory lockFactory;

  private static final String TIMEOUT_CONFIGURATION = "Timeout Configuration";

  /**
   * The directory to be considered as the root of every relative path used with this connector. If not provided, it will default
   * to the remote server default.
   */
  @Parameter
  @Optional
  @Summary("The directory to be considered as the root of every relative path used with this connector")
  @DisplayName("Working Directory")
  private String workingDir = null;

  @ParameterGroup(name = TIMEOUT_CONFIGURATION)
  private TimeoutSettings timeoutSettings = new TimeoutSettings();

  /**
   * Invokes the {@link FtpFileSystem#disconnect()} method on the given {@code ftpFileSystem}
   *
   * @param ftpFileSystem a {@link FtpFileSystem} instance
   */
  @Override
  public void disconnect(FtpFileSystem ftpFileSystem) {
    ftpFileSystem.disconnect();
  }

  /**
   * Validates the connection by delegating into {@link FtpFileSystem#validateConnection()}
   *
   * @param ftpFileSystem the connection to validate
   * @return a {@link ConnectionValidationResult}
   */
  @Override
  public ConnectionValidationResult validate(FtpFileSystem ftpFileSystem) {
    return ftpFileSystem.validateConnection();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getWorkingDir() {
    return workingDir;
  }

  @ParameterGroup(name = CONNECTION)
  private FtpConnectionSettings connectionSettings;

  /**
   * The transfer mode to be used. Currently {@code BINARY} and {@code ASCII} are supported.
   * <p>
   * Defaults to {@code BINARY}
   */
  @Parameter
  @Optional(defaultValue = "BINARY")
  @Summary("Transfer mode to be used")
  private FtpTransferMode transferMode;

  /**
   * Whether to use passive mode. Set to {@code false} to switch to active mode.
   * <p>
   * Defaults to {@code true}.
   */
  @Parameter
  @Optional(defaultValue = "true")
  @Summary("Whether to use passive mode. Set to \"false\" to switch to active mode")
  private boolean passive = true;

  /**
   * Creates and returns a new instance of {@link FtpFileSystem}
   *
   * @return a {@link FtpFileSystem}
   */
  @Override
  public FtpFileSystem connect() throws ConnectionException {
    return new FtpFileSystem(setupClient(), getWorkingDir(), lockFactory);
  }

  private FTPClient setupClient() throws ConnectionException {
    FTPClient client = createClient();

    client.setRemoteVerificationEnabled(isRemoteVerificationEnabled());

    if (getConnectionTimeout() != null && getConnectionTimeoutUnit() != null) {
      client.setConnectTimeout(new Long(getConnectionTimeoutUnit().toMillis(getConnectionTimeout())).intValue());
    }

    if (LOGGER.isDebugEnabled()) {
      LOGGER.debug(format("Connecting to host: '%s' at port: '%d'", connectionSettings.getHost(), connectionSettings.getPort()));
    }

    try {
      client.connect(connectionSettings.getHost(), connectionSettings.getPort());
      if (!FTPReply.isPositiveCompletion(client.getReplyCode())) {
        throw handleClientReplyCode(client.getReplyCode());
      }
      if (!client.login(connectionSettings.getUsername(), connectionSettings.getPassword())) {
        throw handleClientReplyCode(client.getReplyCode());
      }
    } catch (SocketTimeoutException e) {
      throw new FTPConnectionException(getErrorMessage(connectionSettings, e.getMessage()), e, CONNECTION_TIMEOUT);
    } catch (ConnectException e) {
      throw new FTPConnectionException(getErrorMessage(connectionSettings, e.getMessage()), e, CANNOT_REACH);
    } catch (UnknownHostException e) {
      throw new FTPConnectionException(getErrorMessage(connectionSettings, e.getMessage()), e, UNKNOWN_HOST);
    } catch (Exception e) {
      throw client.getReplyCode() != 0
          ? handleClientReplyCode(client.getReplyCode(), e)
          : new ConnectionException(getErrorMessage(connectionSettings, e.getMessage()), e);
    }

    return client;
  }
  
  protected FTPClient createClient() {
    return new FTPClient();
  }

  @Override
  public void onBorrow(FtpFileSystem connection) {
    connection.setTransferMode(transferMode);
    connection.setResponseTimeout(getResponseTimeout(), getResponseTimeoutUnit());
    connection.setPassiveMode(passive);
  }

  /**
   * Handles a {@link FTPClient} reply code and returns a {@link FTPConnectionException} specifying the correspondent
   * {@link FileError} indicating the cause of the failure.
   *
   * @param replyCode FTP Client reply code
   * @return A {@link FTPConnectionException} specifying the error cause with a {@link FileError}
   */
  private ConnectionException handleClientReplyCode(int replyCode) {
    return handleClientReplyCode(replyCode, null);
  }

  /**
   * Handles a {@link FTPClient} reply code and returns a {@link FTPConnectionException} specifying the correspondent
   * {@link FileError} indicating the cause of the failure.
   *
   * @param replyCode FTP Client reply code
   * @param cause the cause exception
   * @return A {@link FTPConnectionException} specifying the error cause with a {@link FileError}
   */
  protected ConnectionException handleClientReplyCode(int replyCode, Throwable cause) {
    switch (replyCode) {
      case 501:
      case 530:
        return new FTPConnectionException(getErrorMessage(replyCode, "User cannot log in"),
                                          INVALID_CREDENTIALS);
      case 421:
        return new FTPConnectionException(getErrorMessage(replyCode, "Service is unavailable"),
                                          SERVICE_NOT_AVAILABLE);
    }
    if (cause != null) {
      return new FTPConnectionException(getErrorMessage(connectionSettings, format("Error code: '%d'", replyCode)), cause,
                                        CONNECTIVITY);
    }

    return new FTPConnectionException(getErrorMessage(connectionSettings, format("Error code: '%d'", replyCode)));
  }

  private String getErrorMessage(FtpConnectionSettings connectionSettings, String message) {
    return format(FTP_ERROR_MESSAGE_MASK, connectionSettings.getHost(), connectionSettings.getPort(), message);
  }

  protected String getErrorMessage(int replyCode, String message) {
    return format(FTP_ERROR_MESSAGE_MASK, connectionSettings.getHost(), connectionSettings.getPort(),
                  format(ERROR_CODE_MASK, replyCode, message));
  }

  protected Integer getConnectionTimeout() {
    return timeoutSettings.getConnectionTimeout();
  }

  protected TimeUnit getConnectionTimeoutUnit() {
    return timeoutSettings.getConnectionTimeoutUnit();
  }

  protected Integer getResponseTimeout() {
    return timeoutSettings.getResponseTimeout();
  }

  protected TimeUnit getResponseTimeoutUnit() {
    return timeoutSettings.getResponseTimeoutUnit();
  }

  public void setConnectionTimeout(Integer connectionTimeout) {
    timeoutSettings.setConnectionTimeout(connectionTimeout);
  }

  public void setConnectionTimeoutUnit(TimeUnit connectionTimeoutUnit) {
    timeoutSettings.setConnectionTimeoutUnit(connectionTimeoutUnit);
  }

  public void setResponseTimeout(Integer responseTimeout) {
    timeoutSettings.setResponseTimeout(responseTimeout);
  }

  public void setResponseTimeoutUnit(TimeUnit responseTimeoutUnit) {
    timeoutSettings.setResponseTimeoutUnit(responseTimeoutUnit);
  }

  public boolean isRemoteVerificationEnabled() {
      return connectionSettings.isRemoteVerificationEnabled();
  }

}
