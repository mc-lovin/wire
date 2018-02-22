/*
 * Copyright 2013 Square Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.wire;

import com.squareup.wire.java.JavaGenerator;
import com.squareup.wire.java.Profile;
import com.squareup.wire.java.ProfileLoader;
import com.squareup.wire.kotlin.KotlinGenerator;
import com.squareup.wire.schema.IdentifierSet;
import com.squareup.wire.schema.ProtoFile;
import com.squareup.wire.schema.Schema;
import com.squareup.wire.schema.SchemaLoader;
import com.squareup.wire.schema.Type;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.google.common.base.Preconditions.checkState;

/**
 * Command line interface to the Wire Java generator.
 *
 * <h3>Usage:</h3>
 *
 * <pre>
 * java WireCompiler --proto_path=&lt;path&gt; --java_out=&lt;path&gt;
 *     [--files=&lt;protos.include&gt;]
 *     [--includes=&lt;message_name&gt;[,&lt;message_name&gt;...]]
 *     [--excludes=&lt;message_name&gt;[,&lt;message_name&gt;...]]
 *     [--quiet]
 *     [--dry_run]
 *     [--android]
 *     [--android-annotations]
 *     [--compact]
 *     [file [file...]]
 * </pre>
 *
 * <p>If the {@code --includes} flag is present, its argument must be a comma-separated list
 * of fully-qualified message or enum names. The output will be limited to those messages
 * and enums that are (transitive) dependencies of the listed names. The {@code --excludes} flag
 * excludes types, and takes precedence over {@code --includes}.
 *
 * <p>If the {@code --registry_class} flag is present, its argument must be a Java class name. A
 * class with the given name will be generated, containing a constant list of all extension
 * classes generated during the compile. This list is suitable for passing to Wire's constructor
 * at runtime for constructing its internal extension registry.
 *
 * <p>If {@code --quiet} is specified, diagnostic messages to stdout are suppressed.
 *
 * <p>The {@code --dry_run} flag causes the compile to just emit the names of the source files that
 * would be generated to stdout.
 *
 * <p>The {@code --android} flag will cause all messages to implement the {@code Parcelable}
 * interface. This implies {@code --android-annotations} as well.
 *
 * <p>The {@code --android-annotations} flag will add the {@code Nullable} annotation to optional
 *  * fields.
 *
 * <p>The {@code --compact} flag will emit code that uses reflection for reading, writing, and
 * toString methods which are normally implemented with code generation.
 */
public final class WireCompiler {
  public static final String PROTO_PATH_FLAG = "--proto_path=";
  public static final String JAVA_OUT_FLAG = "--java_out=";
  public static final String KOTLIN_OUT_FLAG = "--kotlin_out=";
  public static final String FILES_FLAG = "--files=";
  public static final String INCLUDES_FLAG = "--includes=";
  public static final String EXCLUDES_FLAG = "--excludes=";
  public static final String QUIET_FLAG = "--quiet";
  public static final String DRY_RUN_FLAG = "--dry_run";
  public static final String NAMED_FILES_ONLY = "--named_files_only";
  public static final String ANDROID = "--android";
  public static final String ANDROID_ANNOTATIONS = "--android-annotations";
  public static final String COMPACT = "--compact";
  public static final int MAX_WRITE_CONCURRENCY = 8;

  static final String CODE_GENERATED_BY_WIRE =
      "Code generated by Wire protocol buffer compiler, do not edit.";
  private static final String DESCRIPTOR_PROTO = "google/protobuf/descriptor.proto";

  private final FileSystem fs;
  private final WireLogger log;

  final List<String> protoPaths;
  final String javaOut;
  final String kotlinOut;
  final List<String> sourceFileNames;
  final IdentifierSet identifierSet;
  final boolean dryRun;
  final boolean namedFilesOnly;
  final boolean emitAndroid;
  final boolean emitAndroidAnnotations;
  final boolean emitCompact;

  WireCompiler(FileSystem fs, WireLogger log, List<String> protoPaths, String javaOut,
      String kotlinOut, List<String> sourceFileNames, IdentifierSet identifierSet, boolean dryRun,
      boolean namedFilesOnly, boolean emitAndroid, boolean emitAndroidAnnotations,
      boolean emitCompact) {
    this.fs = fs;
    this.log = log;
    this.protoPaths = protoPaths;
    this.javaOut = javaOut;
    this.kotlinOut = kotlinOut;
    this.sourceFileNames = sourceFileNames;
    this.identifierSet = identifierSet;
    this.dryRun = dryRun;
    this.namedFilesOnly = namedFilesOnly;
    this.emitAndroid = emitAndroid;
    this.emitAndroidAnnotations = emitAndroidAnnotations;
    this.emitCompact = emitCompact;
  }

  public static void main(String... args) throws IOException {
    try {
      WireCompiler wireCompiler = forArgs(args);
      wireCompiler.compile();
    } catch (WireException e) {
      System.err.print("Fatal: ");
      e.printStackTrace(System.err);
      System.exit(1);
    }
  }

  static WireCompiler forArgs(String... args) throws WireException {
    return forArgs(FileSystems.getDefault(), new ConsoleWireLogger(), args);
  }

  static WireCompiler forArgs(
      FileSystem fileSystem, WireLogger logger, String... args) throws WireException {
    List<String> sourceFileNames = new ArrayList<>();
    IdentifierSet.Builder identifierSetBuilder = new IdentifierSet.Builder();
    List<String> protoPaths = new ArrayList<>();
    String javaOut = null;
    String kotlinOut = null;
    boolean quiet = false;
    boolean dryRun = false;
    boolean namedFilesOnly = false;
    boolean emitAndroid = false;
    boolean emitAndroidAnnotations = false;
    boolean emitCompact = false;

    for (String arg : args) {
      if (arg.startsWith(PROTO_PATH_FLAG)) {
        protoPaths.add(arg.substring(PROTO_PATH_FLAG.length()));
      } else if (arg.startsWith(JAVA_OUT_FLAG)) {
        checkState(javaOut == null, "java_out already set");
        javaOut = arg.substring(JAVA_OUT_FLAG.length());
      } else if (arg.startsWith(KOTLIN_OUT_FLAG)) {
        checkState(kotlinOut == null, "kotlin_out already set");
        kotlinOut = arg.substring(KOTLIN_OUT_FLAG.length());
      } else if (arg.startsWith(FILES_FLAG)) {
        File files = new File(arg.substring(FILES_FLAG.length()));
        String[] fileNames;
        try {
          fileNames = new Scanner(files, "UTF-8").useDelimiter("\\A").next().split("\n");
        } catch (FileNotFoundException ex) {
          throw new WireException("Error processing argument " + arg, ex);
        }
        sourceFileNames.addAll(Arrays.asList(fileNames));
      } else if (arg.startsWith(INCLUDES_FLAG)) {
        for (String identifier : splitArg(arg, INCLUDES_FLAG.length())) {
          identifierSetBuilder.include(identifier);
        }
      } else if (arg.startsWith(EXCLUDES_FLAG)) {
        for (String identifier : splitArg(arg, EXCLUDES_FLAG.length())) {
          identifierSetBuilder.exclude(identifier);
        }
      } else if (arg.equals(QUIET_FLAG)) {
        quiet = true;
      } else if (arg.equals(DRY_RUN_FLAG)) {
        dryRun = true;
      } else if (arg.equals(NAMED_FILES_ONLY)) {
        namedFilesOnly = true;
      } else if (arg.equals(ANDROID)) {
        emitAndroid = true;
      } else if (arg.equals(ANDROID_ANNOTATIONS)) {
        emitAndroidAnnotations = true;
      } else if (arg.equals(COMPACT)) {
        emitCompact = true;
      } else if (arg.startsWith("--")) {
        throw new IllegalArgumentException("Unknown argument '" + arg + "'.");
      } else {
        sourceFileNames.add(arg);
      }
    }

    if ((javaOut != null) == (kotlinOut != null)) {
      throw new WireException(
          "Only one of " + JAVA_OUT_FLAG + " or " + KOTLIN_OUT_FLAG + " flag must be specified");
    }

    logger.setQuiet(quiet);

    return new WireCompiler(fileSystem, logger, protoPaths, javaOut, kotlinOut, sourceFileNames,
        identifierSetBuilder.build(), dryRun, namedFilesOnly, emitAndroid, emitAndroidAnnotations,
        emitCompact);
  }

  private static List<String> splitArg(String arg, int flagLength) {
    return Arrays.asList(arg.substring(flagLength).split(","));
  }

  void compile() throws IOException {
    SchemaLoader schemaLoader = new SchemaLoader();
    for (String protoPath : protoPaths) {
      schemaLoader.addSource(fs.getPath(protoPath));
    }
    for (String sourceFileName : sourceFileNames) {
      schemaLoader.addProto(sourceFileName);
    }
    Schema schema = schemaLoader.load();

    if (!identifierSet.isEmpty()) {
      log.info("Analyzing dependencies of root types.");
      schema = schema.prune(identifierSet);
      for (String rule : identifierSet.unusedIncludes()) {
        log.info("Unused include: " + rule);
      }
      for (String rule : identifierSet.unusedExcludes()) {
        log.info("Unused exclude: " + rule);
      }
    }

    ConcurrentLinkedQueue<Type> types = new ConcurrentLinkedQueue<>();
    for (ProtoFile protoFile : schema.protoFiles()) {
      // Check if we're skipping files not explicitly named.
      if (!sourceFileNames.isEmpty() && !sourceFileNames.contains(protoFile.location().path())) {
        if (namedFilesOnly || protoFile.location().path().equals(DESCRIPTOR_PROTO)) continue;
      }
      types.addAll(protoFile.types());
    }

    ExecutorService executor = Executors.newCachedThreadPool();
    List<Future<Void>> futures = new ArrayList<>(MAX_WRITE_CONCURRENCY);

    if (javaOut != null) {
      String profileName = emitAndroid ? "android" : "java";
      Profile profile = new ProfileLoader(profileName).schema(schema).load();

      JavaGenerator javaGenerator = JavaGenerator.get(schema)
          .withProfile(profile)
          .withAndroid(emitAndroid)
          .withAndroidAnnotations(emitAndroidAnnotations)
          .withCompact(emitCompact);

      for (int i = 0; i < MAX_WRITE_CONCURRENCY; ++i) {
        JavaFileWriter task = new JavaFileWriter(javaOut, javaGenerator, types, dryRun, fs, log);
        futures.add(executor.submit(
            task));
      }
    } else if (kotlinOut != null) {
      KotlinGenerator kotlinGenerator = KotlinGenerator.get(schema, emitAndroid);

      for (int i = 0; i < MAX_WRITE_CONCURRENCY; ++i) {
        KotlinFileWriter task =
            new KotlinFileWriter(kotlinOut, kotlinGenerator, types, fs, log, dryRun);
        futures.add(executor.submit(task));
      }
    } else {
      throw new AssertionError();
    }

    executor.shutdown();

    try {
      for (Future<Void> future : futures) {
        future.get();
      }
    } catch (ExecutionException e) {
      throw new IOException(e.getMessage(), e);
    } catch (InterruptedException e) {
      throw new RuntimeException(e.getMessage(), e);
    }
  }
}
