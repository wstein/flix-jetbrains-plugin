package de.wstein.flixplugin;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Reading the source-to-class index a {@code --Xdebug} build writes.
 *
 * <p>
 * The shapes here are the compiler's own output, taken from a real build: an
 * absolute path for a
 * file on disk, a bare name for a library source, and a class listed under more
 * than one file where
 * inlining put a foreign file's lines into it.
 */
public class FlixDebugIndexTest {

  private static final String INDEX = """
      {
        "formatVersion":1,
        "sources":{
          "/home/w/lab/src/Main.flix": ["dev.flix.gen.Def$main","dev.flix.gen.Clo$main$626ZYxrpg1N"],
          "/home/w/lab/src/Helper.flix": ["dev.flix.gen.Def$twice"],
          "Prelude.flix": ["dev.flix.gen.Def$println$Gupt94MLFJw"]
        }
      }
      """;

  @Test
  public void namesTheClassesOfASourceOnDisk() throws IOException {
    FlixDebugIndex index = read(INDEX);

    assertEquals(
        List.of("dev.flix.gen.Def$main", "dev.flix.gen.Clo$main$626ZYxrpg1N"),
        index.classesFor("Main.flix"));
  }

  @Test
  public void matchesOnTheFileNameBecauseThatIsWhatABreakpointHas() throws IOException {
    // The keys are the source names the compiler emitted -- an absolute path for a
    // file on disk,
    // a bare name for a library source -- and a breakpoint knows the file it is in.
    // Comparing on
    // the last segment answers both without needing to know which kind a key is.
    FlixDebugIndex index = read(INDEX);

    assertEquals(index.classesFor("Main.flix"), index.classesFor("/home/w/lab/src/Main.flix"));
    assertEquals(List.of("dev.flix.gen.Def$println$Gupt94MLFJw"), index.classesFor("Prelude.flix"));
  }

  @Test
  public void aFileNobodyIndexedNamesNothing() throws IOException {
    // Which the caller must not read as "no classes": it watches everything
    // instead.
    assertTrue(read(INDEX).classesFor("Absent.flix").isEmpty());
  }

  @Test
  public void aBuildWithoutAnIndexReadsAsEmpty() throws IOException {
    // No build yet, a build without `--Xdebug`, or one made by a compiler that
    // predates the
    // index. None of them is an error.
    Path project = Files.createTempDirectory("flix-index-test");

    assertTrue(FlixDebugIndex.read(project).isEmpty());
  }

  @Test
  public void anIndexWrittenInAShapeThisDoesNotKnowIsIgnored() throws IOException {
    // A format bump means the entries may mean something else. Reading them anyway
    // would be
    // guessing, and the fallback -- watch every class -- is always correct.
    FlixDebugIndex index = read(INDEX.replace("\"formatVersion\":1", "\"formatVersion\":2"));

    assertTrue(index.isEmpty());
  }

  @Test
  public void aClassInlinedFromAnotherFileIsFoundUnderBoth() throws IOException {
    // The many-to-many half: one class may carry code from several files, and a
    // breakpoint in
    // any of them has to be told about it.
    FlixDebugIndex index = read("""
        {
          "formatVersion":1,
          "sources":{
            "Prelude.flix": ["dev.flix.gen.Def$println$Gupt94MLFJw"],
            "ToString.flix": ["dev.flix.gen.Def$println$Gupt94MLFJw"]
          }
        }
        """);

    assertEquals(List.of("dev.flix.gen.Def$println$Gupt94MLFJw"), index.classesFor("Prelude.flix"));
    assertEquals(List.of("dev.flix.gen.Def$println$Gupt94MLFJw"), index.classesFor("ToString.flix"));
  }

  private FlixDebugIndex read(String contents) throws IOException {
    Path project = Files.createTempDirectory("flix-index-test");
    Path index = project.resolve(FlixDebugIndex.INDEX_PATH);
    Files.createDirectories(index.getParent());
    Files.writeString(index, contents);
    return FlixDebugIndex.read(project);
  }
}
