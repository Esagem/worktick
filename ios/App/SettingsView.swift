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
        await model.updateSettings(
            hourlyRate: rate,
            eventTitle: title.isEmpty ? nil : title
        )
        await model.setSelectedCalendars(Array(selected))
    }
}
