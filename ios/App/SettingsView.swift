// SettingsView.swift
// User-editable settings: hourly rate, event title, calendar selection, and a
// "Refresh now" action. Mirrors the editable rows on the Android dashboard
// (MainActivity.editHourlyRate / editEventTitle).

import SwiftUI
import UIKit
import EventKit

struct SettingsView: View {
    @EnvironmentObject var model: WTAppModel
    @Environment(\.dismiss) private var dismiss

    @State private var rateText: String = ""
    @State private var titleText: String = ""
    @State private var calendars: [EKCalendar] = []
    @State private var selected: Set<String> = []
    @State private var liveActivityOn: Bool = false
    @State private var portalURLText: String = ""
    @State private var leadMinutesText: String = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("Earnings") {
                    HStack {
                        Text("Hourly rate")
                        Spacer()
                        TextField("30.00", text: $rateText)
                            .keyboardType(.decimalPad)
                            .multilineTextAlignment(.trailing)
                            .frame(width: 100)
                    }
                }
                Section("Calendar event") {
                    HStack {
                        Text("Event title")
                        Spacer()
                        TextField("Title", text: $titleText)
                            .multilineTextAlignment(.trailing)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.sentences)
                    }
                    Text("Case-insensitive exact match. Set this to the title used by your work calendar's events.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Section("Calendars to scan") {
                    if calendars.isEmpty {
                        Text("No calendars available — grant Calendar access first.")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(calendars, id: \.calendarIdentifier) { cal in
                            Button {
                                toggle(cal.calendarIdentifier)
                            } label: {
                                HStack {
                                    Circle()
                                        .fill(Color(cgColor: cal.cgColor ?? UIColor.gray.cgColor))
                                        .frame(width: 10, height: 10)
                                    Text(cal.title).foregroundStyle(.primary)
                                    Spacer()
                                    if selected.isEmpty || selected.contains(cal.calendarIdentifier) {
                                        Image(systemName: "checkmark").foregroundStyle(.blue)
                                    }
                                }
                            }
                        }
                        Text(selected.isEmpty
                             ? "All calendars (default)."
                             : "Selected \(selected.count) of \(calendars.count) calendars.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Section("Clock-in / out reminders") {
                    HStack {
                        Text("Portal URL")
                        Spacer()
                        TextField("https://your-portal.example.com", text: $portalURLText)
                            .keyboardType(.URL)
                            .textContentType(.URL)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                            .multilineTextAlignment(.trailing)
                    }
                    HStack {
                        Text("Lead time (minutes)")
                        Spacer()
                        TextField("0", text: $leadMinutesText)
                            .keyboardType(.numberPad)
                            .multilineTextAlignment(.trailing)
                            .frame(width: 80)
                    }
                    Text("Pings you at every block start and end. Tapping the notification opens the portal URL. Lead time = minutes before the boundary; 0 fires right at the boundary.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Button("Enable notifications") {
                        Task { _ = await WTClockReminderScheduler.shared.requestAuthorization() }
                    }
                }
                Section("Live Activity") {
                    Toggle("Show on lock screen / Dynamic Island", isOn: $liveActivityOn)
                    Text("Off by default. When on, the lock screen and Dynamic Island show a real-time money ticker (sub-second updates) while you're working. The home screen widget always works regardless of this setting.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Section("Permissions") {
                    HStack {
                        Image(systemName: model.authStatus.isUsable ? "checkmark.circle.fill" : "xmark.octagon.fill")
                            .foregroundStyle(model.authStatus.isUsable ? .green : .orange)
                        Text("Calendar access")
                        Spacer()
                        Text(model.authStatus.isUsable ? "Granted" : "Not granted")
                            .foregroundStyle(.secondary)
                    }
                    if !model.authStatus.isUsable {
                        if model.authStatus == .notDetermined {
                            Button("Grant access") {
                                Task {
                                    await model.requestAccess()
                                    refreshCalendars()
                                }
                            }
                        } else {
                            Button("Open iOS Settings") {
                                if let url = URL(string: UIApplication.openSettingsURLString) {
                                    UIApplication.shared.open(url)
                                }
                            }
                        }
                    }
                }
                Section {
                    Button {
                        Task {
                            await commit()
                            await model.refresh()
                            dismiss()
                        }
                    } label: {
                        if model.isRefreshing {
                            HStack { ProgressView(); Text("Refreshing…") }
                        } else {
                            Text("Save & refresh now")
                        }
                    }
                    .disabled(model.isRefreshing)
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") {
                        Task {
                            await commit()
                            dismiss()
                        }
                    }
                }
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { dismiss() }
                }
            }
            .task { load() }
        }
    }

    private func load() {
        rateText = String(format: "%.2f", model.settingsRef.hourlyRate)
        titleText = model.settingsRef.eventTitle
        selected = Set(model.settingsRef.selectedCalendarIDs)
        liveActivityOn = model.settingsRef.liveActivityEnabled
        portalURLText = model.settingsRef.portalURL
        leadMinutesText = String(model.settingsRef.notifyLeadMinutes)
        refreshCalendars()
    }

    private func refreshCalendars() {
        calendars = model.pollerRef.availableCalendars()
    }

    private func toggle(_ id: String) {
        // Empty set means "all calendars". First tap on an unselected one
        // initializes the set with all-but-this-one's-state inverted.
        if selected.isEmpty {
            // Convert "all" → explicit set of every calendar id, then mutate.
            selected = Set(calendars.map(\.calendarIdentifier))
        }
        if selected.contains(id) {
            selected.remove(id)
        } else {
            selected.insert(id)
        }
        // If user selected literally all, collapse back to "use all" sentinel.
        if selected.count == calendars.count {
            selected = []
        }
    }

    private func commit() async {
        let rate = Double(rateText.replacingOccurrences(of: ",", with: ".")) ?? model.settingsRef.hourlyRate
        let title = titleText.trimmingCharacters(in: .whitespacesAndNewlines)
        model.settingsRef.liveActivityEnabled = liveActivityOn

        let trimmedURL = portalURLText.trimmingCharacters(in: .whitespacesAndNewlines)
        let urlIsValid = trimmedURL.isEmpty ||
            trimmedURL.lowercased().hasPrefix("http://") ||
            trimmedURL.lowercased().hasPrefix("https://")
        if urlIsValid {
            model.settingsRef.portalURL = trimmedURL
        }
        if let lead = Int(leadMinutesText.trimmingCharacters(in: .whitespacesAndNewlines)),
           lead >= 0, lead <= 120 {
            model.settingsRef.notifyLeadMinutes = lead
        }

        // If the user just configured reminders, prompt for permission now so
        // the next reschedule actually lands notifications on the lock screen.
        if !trimmedURL.isEmpty || model.settingsRef.notifyLeadMinutes > 0 {
            _ = await WTClockReminderScheduler.shared.requestAuthorization()
        }

        await model.updateSettings(
            hourlyRate: rate,
            eventTitle: title.isEmpty ? nil : title
        )
        await model.setSelectedCalendars(Array(selected))
        // model.updateSettings → refresh() already reschedules reminders, but
        // call once more here in case neither hourlyRate nor eventTitle changed
        // and refresh short-circuits.
        await WTClockReminderScheduler.shared.rescheduleAllAsync(
            schedule: model.schedule,
            settings: model.settingsRef
        )
    }
}
