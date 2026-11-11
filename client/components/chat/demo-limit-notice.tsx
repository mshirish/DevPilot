import { Sparkles } from "lucide-react";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";

/** Replaces the composer once a demo visitor has used all their questions. */
export function DemoLimitNotice({ message }: { message: string }) {
  return (
    <div className="border-t bg-background/80 p-4 backdrop-blur">
      <div className="mx-auto max-w-3xl">
        <Alert role="status">
          <Sparkles />
          <AlertTitle>Demo question limit reached</AlertTitle>
          <AlertDescription>{message}</AlertDescription>
        </Alert>
      </div>
    </div>
  );
}
