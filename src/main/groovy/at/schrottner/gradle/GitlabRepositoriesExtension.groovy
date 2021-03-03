package at.schrottner.gradle


import at.schrottner.gradle.auths.JobToken
import at.schrottner.gradle.auths.Token
import groovy.transform.CompileStatic
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.ArtifactRepository
import org.gradle.api.artifacts.repositories.AuthenticationContainer
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.credentials.HttpHeaderCredentials
import org.gradle.api.initialization.Settings
import org.gradle.api.plugins.ExtensionContainer
import org.gradle.authentication.http.HttpHeaderAuthentication
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
public class GitlabRepositoriesExtension {
	public static final String NAME = "gitLab"
	public static final String REPOSITORY_PREFIX = "GITLAB-"
	public static final String LOG_PREFIX = "GitLab Repositories"
	private final Logger logger
	private final ExtensionContainer extensions
	private final RepositoryHandler repositories
	private int afterPosition
	private String logPrefix

	String afterRepository = 'MavenLocal'
	boolean applyToProject = true
	boolean applySettingTokens = true
	Map<String, Token> tokens = [:]

	public static final def artifacts = [:]

	GitlabRepositoriesExtension(Settings settings) {
		logPrefix = "$LOG_PREFIX :: Settings ::"
		this.logger = LoggerFactory.getLogger(GitlabRepositoriesExtension.class)
		this.extensions = settings.extensions
		this.repositories = settings.pluginManagement.repositories
		setup()
	}

	GitlabRepositoriesExtension(Project project) {
		logPrefix = "$LOG_PREFIX :: Project ($project.name) ::"
		this.logger = project.logger
		this.extensions = project.extensions
		this.repositories = project.repositories
		setup()
	}

	void setup() {
		logger.info("$logPrefix initializing")
		token(JobToken, {
			it.key = 'jobToken'
			it.value = System.getenv("CI_JOB_TOKEN")
		})
		afterPosition = repositories.indexOf(repositories.findByName(afterRepository))
	}

	void token(Class<? extends Token> tokenClass, Action<Token> action) {
		def token = tokenClass.newInstance();
		action.execute(token)

		logger.info("$logPrefix added $token.name: $token.key")
		tokens.put(token.key, token)
	}

	void setAfterRepository(String afterRepository) {
		this.afterRepository = afterRepository
		afterPosition = repositories.indexOf(repositories.findByName(afterRepository))
	}

	ArtifactRepository maven(String id, String tokenSelector, boolean addToRepositories = true) {
		return maven(id, [tokenSelector].toSet(), addToRepositories)
	}

	/**
	 * Generates a MavenArtifactRepository and adds it to the maven repositories.
	 *
	 * Additionally the generated Repository will be stored in a static variable,
	 * which can later be used to be applied to evaluated projects.
	 *
	 * @param id id of the GitLab Group or Project, where you want to fetch from
	 * @param tokenSelectors an optional list to limit token usage to certain tokens
	 * @param addToRepositories as this function can also be used to generate a repository
	 * 			for publishing, we might do not want to add it to the download repositories
	 * @return
	 */
	ArtifactRepository maven(String id, Set<String> tokenSelectors = tokens.keySet(), boolean addToRepositories = true) {
		if (!id) {
			logger.info("$logPrefix: No ID provided nothing will happen here :)")
			return null
		}

		/*
		TODO:
			Make this name, or at least the prefix configurable
		 */
		def repoName = "$REPOSITORY_PREFIX-$id"
		if (!repositories.findByName(repoName)) {

			/*
			TODO:
				Improve this handling, we could even utilize some kind of ordering from the set, to order it in the same
				way. This way we would gain a lot of flexibility
			 */
			def artifactRepo = generateMavenArtifactRepository(
					repoName,
					id,
					tokens.findAll { key, value ->
						tokenSelectors.contains(key)
					})

			if (!artifactRepo) {
				logger.error(
						"""$LOG_PREFIX: Maven Repository $repoName was not added, as no token could be applied!

####################################################################################
####################################################################################
####################################################################################
Currently you have configured following tokens, but non seem to resolve to an value:
\t- ${tokens.keySet().join("\n\t- ")}

				Thank you!

####################################################################################
####################################################################################
####################################################################################
						""")
				return null
			}
			artifacts[repoName] = artifactRepo

			def repo = repositories.maven(artifactRepo)

			repositories.remove(repo)

			/*
			TODO:
				revisit this approach. Maybe this whole approach with using the maven method of repositories can be
				or should be reworked. It was a fasty working hacky solution
			*/
			if (addToRepositories) {
				repositories.add(++afterPosition, repo)
			}
			return repo
		} else {
			logger.info("$logPrefix: Maven Repository with $repoName already exists!")
			repositories.getByName(repoName)
		}
	}

	private Action<MavenArtifactRepository> generateMavenArtifactRepository(repoName, id, Map<String, Token> tokens) {
		Token token = tokens.values().find { token ->
			token.value
		}
		if (token) {
			logger.info("$logPrefix: Maven Repository $repoName is using ${token['name']}")
			new Action<MavenArtifactRepository>() {
				@Override
				void execute(MavenArtifactRepository mvn) {
					/*
					TODO:
						Make this url configurable, so it can be also used for self hosted gitLab instances.
						Additionally it would be cool, if we could provide a parameter, to select form a range of templates.
						But this is currently out of scope.
					 */
					mvn.url = "https://gitlab.com/api/v4/groups/$id/-/packages/maven"
					mvn.name = repoName

					mvn.credentials(HttpHeaderCredentials) {
						it.name = token.name
						it.value = token.value
					}
					mvn.authentication(new Action<AuthenticationContainer>() {
						@Override
						void execute(AuthenticationContainer authentications) {
							authentications.create('header', HttpHeaderAuthentication)
						}
					})
				}
			}
		}
	}
}