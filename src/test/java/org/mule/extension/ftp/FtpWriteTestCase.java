/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.extension.ftp;

import static java.nio.charset.Charset.availableCharsets;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.junit.rules.ExpectedException.none;
import static org.mule.test.extension.file.common.api.FileTestHarness.HELLO_WORLD;
import static org.mule.extension.file.common.api.FileWriteMode.APPEND;
import static org.mule.extension.file.common.api.FileWriteMode.CREATE_NEW;
import static org.mule.extension.file.common.api.FileWriteMode.OVERWRITE;
import static org.mule.extension.file.common.api.exceptions.FileError.FILE_ALREADY_EXISTS;
import static org.mule.extension.file.common.api.exceptions.FileError.ILLEGAL_PATH;
import static org.mule.extension.ftp.AllureConstants.FtpFeature.FTP_EXTENSION;
import static org.mule.runtime.core.api.util.IOUtils.toByteArray;

import org.junit.Ignore;
import org.junit.Rule;
import org.junit.rules.ExpectedException;
import org.mule.extension.file.common.api.FileWriteMode;
import org.mule.extension.file.common.api.exceptions.FileAlreadyExistsException;
import org.mule.extension.file.common.api.exceptions.IllegalPathException;
import org.mule.runtime.core.api.event.CoreEvent;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import io.qameta.allure.Feature;
import org.junit.Test;

@Feature(FTP_EXTENSION)
public class FtpWriteTestCase extends CommonFtpConnectorTestCase {

  private static final String TEMP_DIRECTORY = "files";

  @Rule
  public ExpectedException expectedException = none();

  @Override
  protected String getConfigFile() {
    return "ftp-write-config.xml";
  }

  @Test
  public void appendOnNotExistingFile() throws Exception {
    doWriteOnNotExistingFile(APPEND);
  }

  @Test
  public void overwriteOnNotExistingFile() throws Exception {
    doWriteOnNotExistingFile(OVERWRITE);
  }

  @Test
  public void createNewOnNotExistingFile() throws Exception {
    doWriteOnNotExistingFile(CREATE_NEW);
  }

  @Test
  public void appendOnExistingFile() throws Exception {
    String content = doWriteOnExistingFile(APPEND);
    assertThat(content, is(HELLO_WORLD + HELLO_WORLD));
  }

  @Test
  public void overwriteOnExistingFile() throws Exception {
    String content = doWriteOnExistingFile(OVERWRITE);
    assertThat(content, is(HELLO_WORLD));
  }

  @Test
  public void createNewOnExistingFile() throws Exception {
    testHarness.expectedError().expectError(NAMESPACE, FILE_ALREADY_EXISTS.getType(), FileAlreadyExistsException.class,
                                            "Use a different write mode or point to a path which doesn't exist");
    doWriteOnExistingFile(CREATE_NEW);
  }

  @Test
  public void appendOnNotExistingParentWithoutCreateFolder() throws Exception {
    testHarness.expectedError().expectError(NAMESPACE, ILLEGAL_PATH.getType(), IllegalPathException.class,
                                            "because path to it doesn't exist");
    doWriteOnNotExistingParentWithoutCreateFolder(APPEND);
  }

  @Test
  public void overwriteOnNotExistingParentWithoutCreateFolder() throws Exception {
    testHarness.expectedError().expectError(NAMESPACE, ILLEGAL_PATH.getType(), IllegalPathException.class,
                                            "because path to it doesn't exist");
    doWriteOnNotExistingParentWithoutCreateFolder(OVERWRITE);
  }

  @Test
  public void createNewOnNotExistingParentWithoutCreateFolder() throws Exception {
    testHarness.expectedError().expectError(NAMESPACE, ILLEGAL_PATH.getType(), IllegalPathException.class,
                                            "because path to it doesn't exist");
    doWriteOnNotExistingParentWithoutCreateFolder(CREATE_NEW);
  }

  @Test
  public void appendNotExistingFileWithCreatedParent() throws Exception {
    doWriteNotExistingFileWithCreatedParent(APPEND);
  }

  @Test
  public void overwriteNotExistingFileWithCreatedParent() throws Exception {
    doWriteNotExistingFileWithCreatedParent(OVERWRITE);
  }

  @Test
  public void createNewNotExistingFileWithCreatedParent() throws Exception {
    doWriteNotExistingFileWithCreatedParent(CREATE_NEW);
  }

  @Test
  public void writeOnReadFile() throws Exception {
    final String filePath = "file";

    testHarness.write(filePath, "overwrite me!");

    CoreEvent event = flowRunner("readAndWrite").withVariable("path", filePath).run();

    assertThat(event.getMessage().getPayload().getValue(), equalTo(HELLO_WORLD));
  }

  @Test
  public void writeStaticContent() throws Exception {
    testHarness.makeDir(TEMP_DIRECTORY);
    String path = Paths.get(testHarness.getWorkingDirectory(), TEMP_DIRECTORY, "test.txt").toString();
    doWrite("writeStaticContent", path, "", CREATE_NEW, false);

    String content = toString(readPath(path).getPayload().getValue());
    assertThat(content, is(HELLO_WORLD));
  }

  @Test
  public void writeWithLock() throws Exception {
    testHarness.makeDir(TEMP_DIRECTORY);
    String path = Paths.get(testHarness.getWorkingDirectory(), TEMP_DIRECTORY, "test.txt").toString();
    doWrite("writeWithLock", path, HELLO_WORLD, CREATE_NEW, false);

    String content = toString(readPath(path).getPayload().getValue());
    assertThat(content, is(HELLO_WORLD));
  }

  //TODO: MULE-16515 ignore this test until issue is fixed.
  @Test
  @Ignore
  public void writeOnLockedFile() throws Exception {
    final String path = "file";
    testHarness.write(path, HELLO_WORLD);
    Exception exception = flowRunner("writeAlreadyLocked").withVariable("path", path).withVariable("createParent", false)
        .withVariable("mode", APPEND)
        .withVariable("encoding", null).withPayload(HELLO_WORLD).runExpectingException();

    Method methodGetErrors = exception.getCause().getClass().getMethod("getErrors");
    Object error = ((List<Object>) methodGetErrors.invoke(exception.getCause())).get(0);
    Method methodGetErrorType = error.getClass().getMethod("getErrorType");
    methodGetErrorType.setAccessible(true);
    Object fileError = methodGetErrorType.invoke(error);
    assertThat(fileError.toString(), is("FILE:FILE_LOCK"));
  }

  @Test
  public void writeWithCustomEncoding() throws Exception {
    final String defaultEncoding = muleContext.getConfiguration().getDefaultEncoding();
    assertThat(defaultEncoding, is(notNullValue()));

    final String customEncoding =
        availableCharsets().keySet().stream().filter(encoding -> !encoding.equals(defaultEncoding)).findFirst().orElse(null);

    assertThat(customEncoding, is(notNullValue()));
    final String filename = "encoding.txt";

    doWrite("write", filename, HELLO_WORLD, CREATE_NEW, false, customEncoding);
    String path = Paths.get(testHarness.getWorkingDirectory()).resolve(filename).toString();
    InputStream content = (InputStream) readPath(path, false).getPayload().getValue();

    assertThat(Arrays.equals(toByteArray(content), HELLO_WORLD.getBytes(customEncoding)), is(true));
  }

  @Test
  public void doWriteFileWithSameNameAsFolder() throws Exception {
    expectedException.expectMessage("because it is a directory");
    testHarness.makeDir(TEMP_DIRECTORY);

    String pathToExistingDirectory = Paths.get(testHarness.getWorkingDirectory(), TEMP_DIRECTORY).toString();
    doWrite(pathToExistingDirectory, HELLO_WORLD, OVERWRITE, false);
  }

  private void doWriteNotExistingFileWithCreatedParent(FileWriteMode mode) throws Exception {
    testHarness.makeDir(TEMP_DIRECTORY);
    String path = Paths.get(testHarness.getWorkingDirectory(), TEMP_DIRECTORY, "a/b/test.txt").toString();

    doWrite(path, HELLO_WORLD, mode, true);

    String content = toString(readPath(path).getPayload().getValue());
    assertThat(content, is(HELLO_WORLD));
  }

  private void doWriteOnNotExistingFile(FileWriteMode mode) throws Exception {
    testHarness.makeDir(TEMP_DIRECTORY);
    String path = Paths.get(testHarness.getWorkingDirectory(), TEMP_DIRECTORY, "test.txt").toString();
    doWrite(path, HELLO_WORLD, mode, false);

    String content = toString(readPath(path));
    assertThat(content, is(HELLO_WORLD));
  }

  private void doWriteOnNotExistingParentWithoutCreateFolder(FileWriteMode mode) throws Exception {
    testHarness.makeDir(TEMP_DIRECTORY);
    String path = Paths.get(testHarness.getWorkingDirectory(), TEMP_DIRECTORY, "a/b/test.txt").toString();
    doWrite(path, HELLO_WORLD, mode, false);
  }

  private String doWriteOnExistingFile(FileWriteMode mode) throws Exception {
    final String filePath = "file";
    testHarness.write(filePath, HELLO_WORLD);

    doWrite(filePath, HELLO_WORLD, mode, false);
    return toString(readPath(filePath).getPayload().getValue());
  }

  public static InputStream getContentStream() {
    return (new InputStream() {

      String text = "Hello World!";
      char[] textArray = text.toCharArray();
      int index = -1;

      @Override
      public int read() throws IOException {
        try {
          Thread.sleep(10);
        } catch (InterruptedException e) {
          fail();
        }
        if (index < text.length() - 1) {
          index++;
          return (int) textArray[index];
        }
        return -1;
      }
    });
  }
}
