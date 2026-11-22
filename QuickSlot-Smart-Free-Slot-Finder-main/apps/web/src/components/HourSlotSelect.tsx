import { SelectField } from "./SelectField";

interface HourSlotSelectProps {
  start: string;
  end: string;
  onChange(start: string, end: string): void;
}

const KNOWN_PRESETS: Record<string, string> = {
  "09:00-10:00": "09:00–10:00 (P1)",
  "10:00-11:00": "10:00–11:00 (P2)",
  "11:15-12:15": "11:15–12:15 (P3 Preset)",
  "12:15-13:15": "12:15–13:15 (P4 Preset)",
  "14:00-16:00": "14:00–16:00 (Lab Block)",
  "16:00-18:00": "16:00–18:00 (Evening Block)",
};

export function HourSlotSelect({ start, end, onChange }: HourSlotSelectProps) {
  // 12 Standard hourly slots: 08:00-09:00 through 19:00-20:00
  const standardSlots = Array.from({ length: 12 }, (_, index) => {
    const hour = index + 8;
    const from = `${String(hour).padStart(2, "0")}:00`;
    const until = `${String(hour + 1).padStart(2, "0")}:00`;
    const key = `${from}-${until}`;
    return {
      value: key,
      label:
        KNOWN_PRESETS[key] ||
        `${String(hour).padStart(2, "0")}:00–${String(hour + 1).padStart(2, "0")}:00`,
    };
  });

  const selectedKey = start && end ? `${start}-${end}` : "";
  const isStandard = standardSlots.some((slot) => slot.value === selectedKey);

  // If a non-standard slot is selected (e.g. preset or custom window), add it so the dropdown is never blank
  const options = [...standardSlots];
  if (selectedKey && !isStandard) {
    const presetLabel = KNOWN_PRESETS[selectedKey];
    const customLabel = presetLabel || `Custom: ${start}–${end}`;
    options.unshift({
      value: selectedKey,
      label: customLabel,
    });
  }

  return (
    <label>
      Time slot
      <SelectField
        aria-label="Time slot"
        required
        value={selectedKey}
        onValueChange={(value) => {
          const [from, until] = value.split("-");
          if (from && until) {
            onChange(from, until);
          }
        }}
        options={options}
      />
    </label>
  );
}
