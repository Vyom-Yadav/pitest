package org.pitest.maven;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.scm.ChangeFile;
import org.apache.maven.scm.ChangeSet;
import org.apache.maven.scm.ScmBranch;
import org.apache.maven.scm.ScmException;
import org.apache.maven.scm.ScmFile;
import org.apache.maven.scm.ScmFileSet;
import org.apache.maven.scm.ScmFileStatus;
import org.apache.maven.scm.command.changelog.ChangeLogScmRequest;
import org.apache.maven.scm.command.changelog.ChangeLogScmResult;
import org.apache.maven.scm.command.status.StatusScmResult;
import org.apache.maven.scm.manager.ScmManager;
import org.apache.maven.scm.repository.ScmRepository;
import org.codehaus.plexus.util.StringUtils;
import org.eclipse.aether.RepositorySystem;
import org.pitest.functional.FCollection;
import org.pitest.mutationtest.config.PluginServices;
import org.pitest.mutationtest.config.ReportOptions;
import org.pitest.mutationtest.tooling.CombinedStatistics;

import javax.inject.Inject;

/**
 * Goal which runs a coverage mutation report only for files that have been
 * modified or introduced locally based on the source control configured in
 * maven.
 */
@Mojo(name = "scmMutationCoverage", 
      defaultPhase = LifecyclePhase.VERIFY, 
      requiresDependencyResolution = ResolutionScope.TEST,
      threadSafe = true)
public class ScmMojo extends AbstractPitMojo {

  private static final int NO_LIMIT = -1;

  @Component
  private ScmManager      manager;

  /**
   * List of scm status to include. Names match those defined by the maven scm
   * plugin.
   *
   * Common values include ADDED,MODIFIED (the defaults) & UNKNOWN.
   */
  @Parameter(property = "include")
  private HashSet<String> include;

  /**
   * Analyze last commit. If set to true analyzes last commited change set.
   */
  @Parameter(property = "analyseLastCommit", defaultValue = "false")
  private boolean analyseLastCommit;


  @Parameter(property = "originBranch")
  private String originBranch;

  @Parameter(property = "destinationBranch", defaultValue = "master")
  private String destinationBranch;

  /**
   * Connection type to use when querying scm for changed files. Can either be
   * "connection" or "developerConnection".
   */
  @Parameter(property = "connectionType", defaultValue = "connection")
  private String          connectionType;

  /**
   * Project basedir
   */
  @Parameter(property = "basedir", required = true)
  private File            basedir;

  /**
   * Base of scm root. For a multi module project this is probably the parent
   * project.
   */
  @Parameter(property = "scmRootDir", defaultValue = "${project.parent.basedir}")
  private File            scmRootDir;

  public ScmMojo(RunPitStrategy executionStrategy,
                 ScmManager manager, Predicate<Artifact> filter,
                 PluginServices plugins,
                 boolean analyseLastCommit,
                 Predicate<MavenProject> nonEmptyProjectCheck,
                 RepositorySystem repositorySystem) {
    super(executionStrategy, filter, plugins, nonEmptyProjectCheck, repositorySystem);
    this.manager = manager;
    this.analyseLastCommit = analyseLastCommit;
  }

  @Inject
  public ScmMojo(RepositorySystem repositorySystem) {
    super(repositorySystem);
  }

  @Override
  protected Optional<CombinedStatistics> analyse() throws MojoExecutionException {

    if (scmRootDir == null) {
      this.scmRootDir = findScmRootDir();
    }

    setTargetClasses(makeConcreteList(findModifiedClassNames()));

    if (this.getTargetClasses().isEmpty()) {
      this.getLog().info(
          "No modified files found - nothing to mutation test, analyseLastCommit=" + this.analyseLastCommit);
      return Optional.empty();
    }

    logClassNames();
    defaultTargetTestsIfNoValueSet();
    final ReportOptions data = new MojoToReportOptionsConverter(this,
        new SurefireConfigConverter(), getFilter()).convert();
    data.setFailWhenNoMutations(false);

    return Optional.ofNullable(this.getGoalStrategy().execute(detectBaseDir(), data,
        getPlugins(), new HashMap<>()));

  }

  private void defaultTargetTestsIfNoValueSet() {
    if (this.getTargetTests() == null || this.getTargetTests().isEmpty()) {
      File tests = new File(this.getProject().getBuild()
      .getTestOutputDirectory());
      setTargetTests(new ArrayList<>(MojoToReportOptionsConverter
          .findOccupiedPackagesIn(tests)));
    }
  }

  private void logClassNames() {
    for (final String each : this.getTargetClasses()) {
      this.getLog().info("Will mutate changed class " + each);
    }
  }

  private List<String> findModifiedClassNames() throws MojoExecutionException {

    final File sourceRoot = new File(this.getProject().getBuild()
        .getSourceDirectory());

    final Stream<String> modifiedPaths = findModifiedPaths().stream()
            .map(pathByScmDir());
    return modifiedPaths.flatMap(new PathToJavaClassConverter(
            sourceRoot.getAbsolutePath()))
            .collect(Collectors.toList());

  }

  private Function<String, String> pathByScmDir() {
    return a -> scmRoot().getAbsolutePath() + "/" + a;
  }

  private File findScmRootDir() {
    MavenProject rootProject = this.getProject();
    while (rootProject.hasParent() && rootProject.getParent().getBasedir() != null) {
      rootProject = rootProject.getParent();
    }
    return rootProject.getBasedir();
  }

  private Set<String> findModifiedPaths() throws MojoExecutionException {
    try {
      final ScmRepository repository = this.manager
          .makeScmRepository(getSCMConnection());
      final File scmRoot = scmRoot();
      this.getLog().info("Scm root dir is " + scmRoot);

      final Set<ScmFileStatus> statusToInclude = makeStatusSet();
      final Set<String> modifiedPaths;
      if (analyseLastCommit) {
        modifiedPaths = lastCommitChanges(statusToInclude, repository, scmRoot);
      } else if (originBranch != null && destinationBranch != null) {
        modifiedPaths = changesBetweenBranchs(originBranch, destinationBranch, statusToInclude, repository, scmRoot);
      } else {
        modifiedPaths = localChanges(statusToInclude, repository, scmRoot);
      }
      return modifiedPaths;
    } catch (final ScmException e) {
      throw new MojoExecutionException("Error while querying scm", e);
    }

  }

  private Set<String> lastCommitChanges(Set<ScmFileStatus> statusToInclude, ScmRepository repository, File scmRoot) throws ScmException {
    ChangeLogScmRequest scmRequest = new ChangeLogScmRequest(repository, new ScmFileSet(scmRoot));
    scmRequest.setLimit(1);
    return pathsAffectedByChange(scmRequest, statusToInclude, 1);
  }

  private Set<String> changesBetweenBranchs(String origine, String destination, Set<ScmFileStatus> statusToInclude, ScmRepository repository, File scmRoot) throws ScmException {
    ChangeLogScmRequest scmRequest = new ChangeLogScmRequest(repository, new ScmFileSet(scmRoot));
    scmRequest.setScmBranch(new ScmBranch(destination + ".." + origine));
    return pathsAffectedByChange(scmRequest, statusToInclude, NO_LIMIT);
  }
  
  private Set<String> pathsAffectedByChange(ChangeLogScmRequest scmRequest, Set<ScmFileStatus> statusToInclude, int limit) throws ScmException {
    Set<String> affected = new LinkedHashSet<>();
    ChangeLogScmResult changeLogScmResult = this.manager.changeLog(scmRequest);
    if (changeLogScmResult.isSuccess()) {
      List<ChangeSet> changeSets = limit(changeLogScmResult.getChangeLog().getChangeSets(),limit);
      for (ChangeSet change : changeSets) {
        List<ChangeFile> files = change.getFiles();
        for (final ChangeFile changeFile : files) {
          if (statusToInclude.contains(changeFile.getAction())) {
            affected.add(changeFile.getName());
          }
        }
      }
    }
    return affected;
  }


  private Set<String> localChanges(Set<ScmFileStatus> statusToInclude, ScmRepository repository, File scmRoot) throws ScmException {
    final StatusScmResult status = this.manager.status(repository,
            new ScmFileSet(scmRoot));
    Set<String> affected = new LinkedHashSet<>();
    for (final ScmFile file : status.getChangedFiles()) {
      if (statusToInclude.contains(file.getStatus())) {
        affected.add(file.getPath());
      }
    }
    return affected;
  }
  
  private List<ChangeSet> limit(List<ChangeSet> changeSets, int limit) {
    if (limit < 0) {
      return changeSets;
    }
    return changeSets.subList(0, limit);
  }


  private Set<ScmFileStatus> makeStatusSet() {
    if ((this.include == null) || this.include.isEmpty()) {
      return new HashSet<>(Arrays.asList(
          ScmStatus.ADDED.getStatus(), ScmStatus.MODIFIED.getStatus()));
    }
    final Set<ScmFileStatus> s = new HashSet<>();
    FCollection.mapTo(this.include, stringToMavenScmStatus(), s);
    return s;
  }

  private static Function<String, ScmFileStatus> stringToMavenScmStatus() {
    return a -> ScmStatus.valueOf(a.toUpperCase()).getStatus();
  }

  private File scmRoot() {
    if (this.scmRootDir != null) {
      return this.scmRootDir;
    }
    return this.basedir;
  }

  private String getSCMConnection() throws MojoExecutionException {

    if (this.getProject().getScm() == null) {
      throw new MojoExecutionException("No SCM Connection configured.");
    }

    final String scmConnection = this.getProject().getScm().getConnection();
    if ("connection".equalsIgnoreCase(this.connectionType)
        && StringUtils.isNotEmpty(scmConnection)) {
      return scmConnection;
    }

    final String scmDeveloper = this.getProject().getScm().getDeveloperConnection();
    if ("developerconnection".equalsIgnoreCase(this.connectionType)
        && StringUtils.isNotEmpty(scmDeveloper)) {
      return scmDeveloper;
    }

    throw new MojoExecutionException("SCM Connection is not set.");

  }

  public void setConnectionType(final String connectionType) {
    this.connectionType = connectionType;
  }

  public void setScmRootDir(final File scmRootDir) {
    this.scmRootDir = scmRootDir;
  }

  /**
   * A bug in maven 2 requires that all list fields declare a concrete list type
   */
  private static ArrayList<String> makeConcreteList(List<String> list) {
    return new ArrayList<>(list);
  }

}
