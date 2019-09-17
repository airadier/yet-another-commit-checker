package com.isroot.stash.plugin;

import com.atlassian.bitbucket.event.branch.BranchCreationHookRequest;
import com.atlassian.bitbucket.hook.repository.PreRepositoryHook;
import com.atlassian.bitbucket.hook.repository.PreRepositoryHookContext;
import com.atlassian.bitbucket.hook.repository.RepositoryHookRequest;
import com.atlassian.bitbucket.hook.repository.RepositoryHookResult;
import com.atlassian.bitbucket.hook.repository.RepositoryHookTrigger;
import com.atlassian.bitbucket.hook.repository.RepositoryPushHookRequest;
import com.atlassian.bitbucket.hook.repository.StandardRepositoryHookTrigger;
import com.atlassian.bitbucket.repository.Branch;
import com.atlassian.bitbucket.setting.Settings;
import com.isroot.stash.plugin.checks.BranchNameCheck;
import com.isroot.stash.plugin.errors.YaccError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * @author Sean Ford
 * @since 2013-05-11
 */
public class YaccHook implements PreRepositoryHook {
    private static final Logger log = LoggerFactory.getLogger(YaccHook.class);

    private final YaccService yaccService;

    public YaccHook(YaccService yaccService) {
        this.yaccService = yaccService;
    }

    @Nonnull
    @Override
    public RepositoryHookResult preUpdate(
            @Nonnull PreRepositoryHookContext context,
            @Nonnull RepositoryHookRequest repositoryHookRequest) {
        log.debug("preUpdate, class={}, trigger={}", repositoryHookRequest.getClass()
                .getSimpleName(), repositoryHookRequest.getTrigger());

        log.debug("yacc settings: {}", context.getSettings().asMap());

        final RepositoryHookTrigger trigger = repositoryHookRequest.getTrigger();

        if (trigger == StandardRepositoryHookTrigger.REPO_PUSH) {
            return handleRepositoryPush(context, (RepositoryPushHookRequest) repositoryHookRequest);
        } else if (trigger == StandardRepositoryHookTrigger.BRANCH_CREATE
                && repositoryHookRequest instanceof BranchCreationHookRequest) {

            // sford: Note: Both trigger BRANCH_CREATE and class BranchCreationHookRequest both
            // need to be checked because BBS has a bug where BranchDeletionHookRequest is marked
            // as BRANCH_CREATE.
            //
            // See: https://jira.atlassian.com/browse/BSERV-11458

            return handleBranchCreation(context.getSettings(),
                    (BranchCreationHookRequest) repositoryHookRequest);
        }

        return RepositoryHookResult.accepted();
    }

    private RepositoryHookResult handleRepositoryPush(
            @Nonnull PreRepositoryHookContext context,
            @Nonnull RepositoryPushHookRequest repositoryPushHookRequest) {
        final Settings settings = context.getSettings();

        long startTime = System.currentTimeMillis();
        log.warn("YACC Check push {} / {}", repositoryPushHookRequest.getRepository().getProject().getName(), repositoryPushHookRequest.getRepository().getName());

        RepositoryHookResult result;
        try {
            result = yaccService.check(context, repositoryPushHookRequest, settings);
        } catch (TimeLimitedMatcherFactory.RegExpTimeoutException e) {
            log.error("YACC Regex timeout for {} / {}", repositoryPushHookRequest.getRepository().getProject().getName(), repositoryPushHookRequest.getRepository().getName());
            log.error("YACC Regex timeout exceeded", e);
            result = RepositoryHookResult.rejected("Regex timeout exceeded", "The timeout for evaluating regular expression has been exceeded");
        }

        long endTime = System.currentTimeMillis();
        long timeElapsed = endTime - startTime;
        log.warn("YACC Finished push {} / {} - " + timeElapsed + "ms" , repositoryPushHookRequest.getRepository().getProject().getName(), repositoryPushHookRequest.getRepository().getName());

        return result;
    }

    static RepositoryHookResult handleBranchCreation(@Nonnull Settings settings,
            @Nonnull BranchCreationHookRequest branchCreationHookRequest) {
        final Branch branch = branchCreationHookRequest.getBranch();

        log.debug("branch creation hook: id={} displayId={}",
                branch.getId(), branch.getDisplayId());

        long startTime = System.currentTimeMillis();
        log.warn("YACC Check branch {} / {}", branchCreationHookRequest.getRepository().getProject().getName(), branchCreationHookRequest.getRepository().getName());

        List<YaccError> errors = new BranchNameCheck(settings, branch.getId()).check();

        long endTime = System.currentTimeMillis();
        long timeElapsed = endTime - startTime;
        log.warn("YACC Finished branch {} / {} - " + timeElapsed + "ms" , branchCreationHookRequest.getRepository().getProject().getName(), branchCreationHookRequest.getRepository().getName());


        if (!errors.isEmpty()) {
            return RepositoryHookResult.rejected(
                    "Branch name does not comply with repository requirements.",
                    errors.get(0).getMessage());
        }

        return RepositoryHookResult.accepted();
    }
}
