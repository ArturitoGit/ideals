package org.rri.ideals.server.codeactions;

import com.google.gson.GsonBuilder;
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.ShowIntentionsPass;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rri.ideals.server.LspPath;
import org.rri.ideals.server.diagnostics.DiagnosticsService;
import org.rri.ideals.server.util.MiscUtil;
import org.rri.ideals.server.util.TextUtil;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.intellij.codeInsight.daemon.impl.ShowIntentionsPass.*;
import static com.intellij.openapi.application.ApplicationManager.getApplication;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;
import static org.rri.ideals.server.util.EditorUtil.createEditor;
import static org.rri.ideals.server.util.MiscUtil.resolvePsiFile;

@Service(Service.Level.PROJECT)
public final class CodeActionService {
  private static final Logger LOG = Logger.getInstance(CodeActionService.class);

  private final @NotNull Project project;

  public CodeActionService(@NotNull Project project) {
    this.project = project;
  }

  @NotNull
  private static CodeAction toCodeAction(@NotNull LspPath path,
                                         @NotNull Range range,
                                         @NotNull HighlightInfo.IntentionActionDescriptor descriptor,
                                         @NotNull String kind) {
    return MiscUtil.with(new CodeAction(ReadAction.compute(() -> descriptor.getAction().getText())), ca -> {
      ca.setKind(kind);
      ca.setData(new ActionData(path.toLspUri(), range));
    });
  }

  @NotNull
  private static <T> Predicate<T> distinctByKey(@NotNull Function<? super T, ?> keyExtractor) {
    Set<Object> seen = new HashSet<>();
    return t -> seen.add(keyExtractor.apply(t));
  }

  @NotNull
  public List<CodeAction> getCodeActions(@NotNull LspPath path, @NotNull Range range) {

    final List<CodeAction> actions = withEditorContext(path, range, context -> {
      waitForUnresolvedQuickfix(context);
      return availableCodeActions(context);
    });

    return ofNullable(actions).orElse(emptyList());
  }

  @NotNull
  public WorkspaceEdit applyCodeAction(@NotNull CodeAction codeAction) {
    final var actionData = new GsonBuilder().create()
        .fromJson(codeAction.getData().toString(), ActionData.class);

    final var path = LspPath.fromLspUri(actionData.getUri());
    final var result = new WorkspaceEdit();

    var disposable = Disposer.newDisposable();

    try {
      final var psiFile = resolvePsiFile(project, path);

      if (psiFile == null) {
        LOG.error("couldn't find PSI file: " + path);
        return result;
      }

      final var oldCopy = ((PsiFile) psiFile.copy());

      getApplication().invokeAndWait(() -> {
        final var editor = createEditor(disposable, psiFile, actionData.getRange().getStart());

        final var quickFixes = diagnostics().getQuickFixes(path, actionData.getRange());
        final var actionInfo = getActionsToShow(editor, psiFile, true);

        var actionFound = Stream.of(
                quickFixes,
                actionInfo.errorFixesToShow,
                actionInfo.inspectionFixesToShow,
                actionInfo.intentionsToShow)
            .flatMap(Collection::stream)
            .map(HighlightInfo.IntentionActionDescriptor::getAction)
            .filter(it -> it.getText().equals(codeAction.getTitle()))
            .findFirst()
            .orElse(null);

        if (actionFound == null) {
          LOG.warn("No action descriptor found: " + codeAction.getTitle());
          return;
        }

        CommandProcessor.getInstance().executeCommand(project, () -> {
          if (actionFound.startInWriteAction()) {
            WriteAction.run(() -> actionFound.invoke(project, editor, psiFile));
          } else {
            actionFound.invoke(project, editor, psiFile);
          }
        }, codeAction.getTitle(), null);
      });

      final var oldDoc = new Ref<Document>();
      final var newDoc = new Ref<Document>();

      ReadAction.run(() -> {
        oldDoc.set(requireNonNull(MiscUtil.getDocument(oldCopy)));
        newDoc.set(requireNonNull(MiscUtil.getDocument(psiFile)));
      });

      final var edits = TextUtil.textEditFromDocs(oldDoc.get(), newDoc.get());

      WriteCommandAction.runWriteCommandAction(project, () -> {
        newDoc.get().setText(oldDoc.get().getText());
        PsiDocumentManager.getInstance(project).commitDocument(newDoc.get());
      });

      if (!edits.isEmpty()) {
        diagnostics().haltDiagnostics(path);  // all cached quick fixes are no longer valid
        result.setChanges(Map.of(actionData.getUri(), edits));
      }
    } finally {
      getApplication().invokeAndWait(() -> Disposer.dispose(disposable));
    }

    diagnostics().launchDiagnostics(path);
    return result;
  }

  @NotNull
  private DiagnosticsService diagnostics() {
    return project.getService(DiagnosticsService.class);
  }

  @SuppressWarnings("UnstableApiUsage")
  private void waitForUnresolvedQuickfix(EditorContext context) {
    DaemonCodeAnalyzerImpl.waitForUnresolvedReferencesQuickFixesUnderCaret(context.file(), context.editor());
  }

  @Nullable
  private List<CodeAction> availableCodeActions(EditorContext context) {
    return computeInEventDispatchThread(() -> {
      final var path = context.path();
      final var range = context.range();

      final var actionInfo = ShowIntentionsPass.getActionsToShow(context.editor(), context.file(), true);

      final var quickFixes = diagnostics().getQuickFixes(path, range).stream()
              .map(it -> toCodeAction(path, range, it, CodeActionKind.QuickFix));

      final var intentionActions = Stream.of(
                      actionInfo.errorFixesToShow,
                      actionInfo.inspectionFixesToShow,
                      actionInfo.intentionsToShow)
              .flatMap(Collection::stream)
              .map(it -> toCodeAction(path, range, it, CodeActionKind.Refactor));

      return Stream.concat(quickFixes, intentionActions)
              .filter(distinctByKey(CodeAction::getTitle))
              .collect(Collectors.toList());
    });
  }

  @Nullable
  private <T> T withEditorContext(@NotNull LspPath path, @NotNull Range range, Function<EditorContext, T> task) {
    final Disposable disposable = Disposer.newDisposable();
    T result;

    try {
      final EditorContext context = createContext(path, range, disposable);
      result = task.apply(context);

    } finally {
      disposable.dispose();
    }
    return result;
  }

  @Nullable
  private EditorContext createContext(@NotNull LspPath path, @NotNull Range range, @NotNull Disposable disposable) {
    return computeInEventDispatchThread(() -> {
      final PsiFile file = requireNonNull(resolvePsiFile(project, path));
      final Editor editor = createEditor(disposable, file, range.getStart());
      return new EditorContext(path, range, file, editor, disposable);
    });
  }

  private record EditorContext(
          LspPath path,
          Range range,
          PsiFile file,
          Editor editor,
          Disposable disposable
  ) {}

  @Nullable
  private <T> T computeInEventDispatchThread(Supplier<T> supplier) {
    Ref<T> result = new Ref<>();
    getApplication().invokeAndWait(() -> result.set(supplier.get()));
    return result.get();
  }
}
