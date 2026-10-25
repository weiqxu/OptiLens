/** Runtime availability comes from the server, including version/provenance checks. */
export function databaseOptions(databases, createOption = (label, id) => new Option(label, id)) {
  return databases.map(database => {
    const available = database.available === true;
    const option = createOption(`${database.title} · ${database.version}${available ? '' : ' · Not installed'}`, database.id);
    option.disabled = !available;
    option.title = database.message || (available ? '' : 'The database runtime is not installed.');
    return option;
  });
}
