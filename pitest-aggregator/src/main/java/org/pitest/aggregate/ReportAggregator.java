package org.pitest.aggregate;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pitest.classpath.CodeSource;
import org.pitest.coverage.BlockCoverage;
import org.pitest.coverage.BlockLocation;
import org.pitest.coverage.CoverageData;
import org.pitest.coverage.CoverageDatabase;
import org.pitest.coverage.TestInfo;
import org.pitest.coverage.analysis.LineMapper;
import org.pitest.functional.F;
import org.pitest.functional.FCollection;
import org.pitest.functional.Option;
import org.pitest.mutationtest.ClassMutationResults;
import org.pitest.mutationtest.MutationMetaData;
import org.pitest.mutationtest.MutationResult;
import org.pitest.mutationtest.MutationResultListener;
import org.pitest.mutationtest.SourceLocator;
import org.pitest.mutationtest.report.html.MutationHtmlReportListener;
import org.pitest.mutationtest.tooling.SmartSourceLocator;
import org.pitest.util.ResultOutputStrategy;

public final class ReportAggregator {
  private final ResultOutputStrategy       resultOutputStrategy;
  private final DataLoader<BlockCoverage>  blockCoverageLoader;
  private final DataLoader<MutationResult> mutationLoader;

  private final Collection<File>           sourceCodeDirectories;
  private final CodeSourceAggregator       codeSourceAggregator;

  private ReportAggregator(final ResultOutputStrategy resultOutputStrategy, final Set<File> lineCoverageFiles, final Set<File> mutationFiles,
      final Set<File> sourceCodeDirs, final Set<File> compiledCodeDirs) {
    this.resultOutputStrategy = resultOutputStrategy;
    this.blockCoverageLoader = new BlockCoverageDataLoader(lineCoverageFiles);
    this.mutationLoader = new MutationResultDataLoader(mutationFiles);
    this.sourceCodeDirectories = Collections.unmodifiableCollection(new HashSet<File>(sourceCodeDirs));
    this.codeSourceAggregator = new CodeSourceAggregator(new HashSet<File>(compiledCodeDirs));
  }

  public void aggregateReport() throws ReportAggregationException {
    final MutationMetaData mutationMetaData = new MutationMetaData(new ArrayList<MutationResult>(mutationLoader.loadData()));

    final MutationResultListener mutationResultListener = createResultListener(mutationMetaData);

    mutationResultListener.runStart();

    for (final ClassMutationResults mutationResults : mutationMetaData.toClassResults()) {
      mutationResultListener.handleMutationResult(mutationResults);
    }
    mutationResultListener.runEnd();
  }

  private MutationResultListener createResultListener(final MutationMetaData mutationMetaData) throws ReportAggregationException {
    final SourceLocator sourceLocator = new SmartSourceLocator(sourceCodeDirectories);

    final CodeSource codeSource = codeSourceAggregator.createCodeSource();
    final CoverageDatabase coverageDatabase = calculateCoverage(codeSource, mutationMetaData);
    final Collection<String> mutatorNames = new HashSet<String>(FCollection.flatMap(mutationMetaData.getMutations(), new F<MutationResult, List<String>>() {
      @Override
      public List<String> apply(final MutationResult a) {
        try {
          final String mutatorName = MutatorUtil.loadMutator(a.getDetails().getMutator()).getName();
          return Collections.singletonList(mutatorName);
        } catch (final Exception e) {
          throw new RuntimeException("Cannot convert to mutator: " + a.getDetails().getMutator(), e);
        }
      }
    }));

    return new MutationHtmlReportListener(coverageDatabase, resultOutputStrategy, mutatorNames, sourceLocator);
  }

  private CoverageData calculateCoverage(final CodeSource codeSource, final MutationMetaData metadata) throws ReportAggregationException {
    final Collection<BlockCoverage> coverageData = blockCoverageLoader.loadData();
    try {
      final CoverageData coverage = new CoverageData(codeSource, new LineMapper(codeSource));

      if (!coverageData.isEmpty()) {
        final Map<BlockLocation, Set<TestInfo>> blockCoverageMap = new HashMap<BlockLocation, Set<TestInfo>>();

        for (final BlockCoverage blockData : coverageData) {
          blockCoverageMap.put(blockData.getBlock(), new HashSet<TestInfo>(FCollection.map(blockData.getTests(), new F<String, TestInfo>() {
            @Override
            public TestInfo apply(final String a) {
              return new TestInfo(null, a, 0, Option.some(blockData.getBlock().getLocation().getClassName()), blockData.getBlock().getBlock());
            }
          })));

          final Field bcMap = CoverageData.class.getDeclaredField("blockCoverage");
          bcMap.setAccessible(true);
          bcMap.set(coverage, blockCoverageMap);
        }
      }

      return coverage;

    } catch (final Exception e) {
      throw new ReportAggregationException(e.getMessage(), e);
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private ResultOutputStrategy resultOutputStrategy;
    private final Set<File>      lineCoverageFiles       = new HashSet<File>();
    private final Set<File>      mutationResultsFiles    = new HashSet<File>();
    private final Set<File>      sourceCodeDirectories   = new HashSet<File>();
    private final Set<File>      compiledCodeDirectories = new HashSet<File>();

    public Builder resultOutputStrategy(final ResultOutputStrategy resultOutputStrategy) {
      this.resultOutputStrategy = resultOutputStrategy;
      return this;
    }

    public Builder lineCoverageFiles(final List<File> lineCoverageFiles) {
      this.lineCoverageFiles.clear();
      for (final File file : lineCoverageFiles) {
        addLineCoverageFile(file);
      }
      return this;
    }

    public Builder addLineCoverageFile(final File lineCoverageFile) {
      validateFile(lineCoverageFile);
      this.lineCoverageFiles.add(lineCoverageFile);
      return this;
    }

    public Builder mutationResultsFiles(final List<File> mutationResultsFiles) {
      this.mutationResultsFiles.clear();
      for (final File file : mutationResultsFiles) {
        addMutationResultsFile(file);
      }
      return this;
    }

    public Builder addMutationResultsFile(final File mutationResultsFile) {
      validateFile(mutationResultsFile);
      this.mutationResultsFiles.add(mutationResultsFile);
      return this;
    }

    public Builder sourceCodeDirectories(final List<File> sourceCodeDirectories) {
      this.sourceCodeDirectories.clear();
      for (final File file : sourceCodeDirectories) {
        addSourceCodeDirectory(file);
      }
      return this;
    }

    public Builder addSourceCodeDirectory(final File sourceCodeDirectory) {
      validateDirectory(sourceCodeDirectory);
      this.sourceCodeDirectories.add(sourceCodeDirectory);
      return this;
    }

    public Builder compiledCodeDirectories(final List<File> compiledCodeDirectories) {
      this.compiledCodeDirectories.clear();
      for (final File file : compiledCodeDirectories) {
        addCompiledCodeDirectory(file);
      }
      return this;
    }

    public Builder addCompiledCodeDirectory(final File compiledCodeDirectory) {
      validateDirectory(compiledCodeDirectory);
      this.compiledCodeDirectories.add(compiledCodeDirectory);
      return this;
    }

    public Set<File> getCompiledCodeDirectories() {
      return compiledCodeDirectories;
    }

    public Set<File> getLineCoverageFiles() {
      return lineCoverageFiles;
    }

    public Set<File> getMutationResultsFiles() {
      return mutationResultsFiles;
    }

    public Set<File> getSourceCodeDirectories() {
      return sourceCodeDirectories;
    }

    public ReportAggregator build() {
      validateState();
      return new ReportAggregator(resultOutputStrategy, lineCoverageFiles, mutationResultsFiles, sourceCodeDirectories, compiledCodeDirectories);
    }

    /*
     * Validators
     */
    private void validateState() {
      if (resultOutputStrategy == null) {
        throw new IllegalStateException("Failed to build: the resultOutputStrategy has not been set");
      }
      if (this.lineCoverageFiles.isEmpty()) {
        throw new IllegalStateException("Failed to build: no lineCoverageFiles have been set");
      }
      if (this.mutationResultsFiles.isEmpty()) {
        throw new IllegalStateException("Failed to build: no mutationResultsFiles have been set");
      }
      if (this.sourceCodeDirectories.isEmpty()) {
        throw new IllegalStateException("Failed to build: no sourceCodeDirectories have been set");
      }
      if (this.compiledCodeDirectories.isEmpty()) {
        throw new IllegalStateException("Failed to build: no compiledCodeDirectories have been set");
      }
    }

    private void validateFile(final File file) {
      if (file == null) {
        throw new IllegalArgumentException("file is null");
      }
      if (!file.exists() || !file.isFile()) {
        throw new IllegalArgumentException(file.getAbsolutePath() + " does not exist or is not a file");
      }
    }

    private void validateDirectory(final File directory) {
      if (directory == null) {
        throw new IllegalArgumentException("directory is null");
      }
      if (!directory.exists() || !directory.isDirectory()) {
        throw new IllegalArgumentException(directory.getAbsolutePath() + " does not exist or is not a directory");
      }
    }
  }
}
