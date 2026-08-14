import * as React from "react";

import { useMutation } from "@tanstack/react-query";
import { LoaderCircle, PackageIcon } from "lucide-react";
import { useTranslation } from "react-i18next";

import { useCurrentAssistant } from "~/hooks/use-current-assistant";
import { usePickerPopover } from "~/hooks/use-picker-popover";
import { getDisplayName } from "~/lib/display";
import { extractErrorMessage } from "~/lib/error";
import { safeStringArray } from "~/lib/type-guards";
import { cn } from "~/lib/utils";
import api from "~/services/api";
import type { QuickMessage } from "~/types";
import { Button } from "~/components/ui/button";
import { Checkbox } from "~/components/ui/checkbox";
import {
  Popover,
  PopoverContent,
  PopoverDescription,
  PopoverHeader,
  PopoverTitle,
  PopoverTrigger,
} from "~/components/ui/popover";
import { ScrollArea } from "~/components/ui/scroll-area";

import { PickerErrorAlert } from "./picker-error-alert";

export interface ExtensionPickerButtonProps {
  disabled?: boolean;
  className?: string;
}

function getQuickMessages(source: unknown): QuickMessage[] {
  if (!Array.isArray(source)) {
    return [];
  }

  return source.filter((item): item is QuickMessage =>
    Boolean(
      item &&
      typeof item === "object" &&
      typeof item.id === "string" &&
      typeof item.content === "string" &&
      item.content.trim().length > 0,
    ),
  );
}

export function ExtensionPickerButton({
  disabled = false,
  className,
}: ExtensionPickerButtonProps) {
  const { t } = useTranslation("input");
  const { settings, currentAssistant } = useCurrentAssistant();

  const canUse = Boolean(settings && currentAssistant && !disabled);
  const { error, setError, popoverProps } = usePickerPopover(canUse);

  const quickMessages = React.useMemo(
    () => getQuickMessages(settings?.quickMessages),
    [settings?.quickMessages],
  );
  const selectedQuickMessageIds = React.useMemo(
    () => safeStringArray(currentAssistant?.quickMessageIds),
    [currentAssistant?.quickMessageIds],
  );

  const selectedCount = selectedQuickMessageIds.length;
  const hasData = quickMessages.length > 0;

  React.useEffect(() => {
    if (!canUse || !hasData) {
      popoverProps.onOpenChange(false);
    }
  }, [canUse, hasData]);

  const updateAssistantExtensionsMutation = useMutation({
    mutationFn: ({
      assistantId,
      quickMessageIds,
    }: {
      assistantId: string;
      quickMessageIds: string[];
      key: string;
    }) =>
      api.post<{ status: string }>("settings/assistant/injections", {
        assistantId,
        quickMessageIds,
      }),
    onError: (updateError) => {
      setError(extractErrorMessage(updateError, t("injection.update_failed")));
    },
    onSuccess: () => setError(null),
  });

  const handleToggleQuickMessage = React.useCallback(
    (id: string, checked: boolean) => {
      if (!canUse || !currentAssistant) return;
      const nextIds = new Set(selectedQuickMessageIds);
      if (checked) nextIds.add(id);
      else nextIds.delete(id);
      updateAssistantExtensionsMutation.mutate({
        assistantId: currentAssistant.id,
        quickMessageIds: Array.from(nextIds),
        key: `quickmessage:${id}`,
      });
    },
    [
      canUse,
      currentAssistant,
      selectedQuickMessageIds,
      updateAssistantExtensionsMutation,
    ],
  );

  if (!hasData) {
    return null;
  }

  return (
    <Popover {...popoverProps}>
      <PopoverTrigger asChild>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          disabled={!canUse || updateAssistantExtensionsMutation.isPending}
          className={cn(
            "h-8 rounded-full px-2 text-muted-foreground hover:text-foreground",
            selectedCount > 0 && "text-primary hover:bg-primary/10",
            className,
          )}
        >
          {updateAssistantExtensionsMutation.isPending ? (
            <LoaderCircle className="size-4 animate-spin" />
          ) : (
            <PackageIcon className="size-4" />
          )}
          {selectedCount > 0 ? (
            <span className="rounded-full bg-primary/10 px-1.5 py-0.5 text-[10px] text-primary">
              {selectedCount}
            </span>
          ) : null}
        </Button>
      </PopoverTrigger>

      <PopoverContent align="end" className="w-[min(92vw,26rem)] gap-0 p-0">
        <PopoverHeader className="border-b px-6 py-4">
          <PopoverTitle>{t("injection.title")}</PopoverTitle>
          <PopoverDescription>{t("injection.description")}</PopoverDescription>
        </PopoverHeader>

        <div className="space-y-4 px-4 py-4">
          <PickerErrorAlert error={error} />

          <ScrollArea className="h-[16rem] pr-3">
            {quickMessages.length > 0 ? (
              <div className="space-y-2">
                {quickMessages.map((item) => {
                  const checked = selectedQuickMessageIds.includes(item.id);
                  const switching =
                    updateAssistantExtensionsMutation.isPending &&
                    updateAssistantExtensionsMutation.variables?.key ===
                      `quickmessage:${item.id}`;

                  return (
                    <label
                      key={item.id}
                      className={cn(
                        "flex cursor-pointer items-center gap-3 rounded-lg border px-3 py-3 transition",
                        checked && "border-primary bg-primary/5",
                      )}
                    >
                      {switching ? (
                        <LoaderCircle className="size-4 animate-spin" />
                      ) : (
                        <Checkbox
                          checked={checked}
                          disabled={disabled || updateAssistantExtensionsMutation.isPending}
                          onCheckedChange={(nextChecked) => {
                            handleToggleQuickMessage(item.id, Boolean(nextChecked));
                          }}
                        />
                      )}
                      <div className="min-w-0">
                        <div className="truncate text-sm font-medium">
                          {getDisplayName(item.title, t("injection.unnamed_quickmessage"))}
                        </div>
                        <div className="text-muted-foreground mt-0.5 line-clamp-2 text-xs">
                          {item.content}
                        </div>
                      </div>
                    </label>
                  );
                })}
              </div>
            ) : (
              <div className="rounded-md border border-dashed px-3 py-8 text-center text-sm text-muted-foreground">
                {t("injection.empty_quickmessages")}
              </div>
            )}
          </ScrollArea>
        </div>
      </PopoverContent>
    </Popover>
  );
}
