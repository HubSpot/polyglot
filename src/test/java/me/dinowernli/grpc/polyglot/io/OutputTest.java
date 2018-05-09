package me.dinowernli.grpc.polyglot.io;

import static com.google.common.truth.Truth.assertThat;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.google.common.io.Files;

import polyglot.ConfigProto.OutputConfiguration;
import polyglot.ConfigProto.OutputConfiguration.Destination;

/** Unit tests for {@link Output}. */
public class OutputTest {
  @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

  @Test
  public void handlesMissingFile() throws Throwable {
    Path filePath = Paths.get(tempFolder.getRoot().getAbsolutePath(), "some-file.txt");
    Output output = Output.forConfiguration(OutputConfiguration.newBuilder()
        .setDestination(Destination.FILE)
        .setFilePath(filePath.toString())
        .build());

    output.write("foo");
    output.close();

    String line = Files.readFirstLine(new File(filePath.toUri()), Charset.defaultCharset());
    assertThat(line).isEqualTo("foo");
  }
}
