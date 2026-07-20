function CreatorsPage({ creators, creatorForm, setCreatorForm, onCreateCreator }) {
  return (
    <article className="card mdx-surface mdx-prose form-card page-stack">
      <p className="mdx-kicker">Creator Directory</p>
      <h3>3. Add creator</h3>
      <div className="mdx-section-rule" />
      <p>Store creator profile details so assignments can be tied and tracked accurately.</p>
      <form onSubmit={onCreateCreator} className="inline-form page-form-grid">
        <input
          type="text"
          value={creatorForm.name}
          placeholder="Creator name"
          onChange={(event) => setCreatorForm((prev) => ({ ...prev, name: event.target.value }))}
          required
        />
        <input
          type="text"
          value={creatorForm.handle}
          placeholder="@handle"
          onChange={(event) => setCreatorForm((prev) => ({ ...prev, handle: event.target.value }))}
          required
        />
        <select
          value={creatorForm.platform}
          onChange={(event) => setCreatorForm((prev) => ({ ...prev, platform: event.target.value }))}
        >
          <option>Instagram</option>
          <option>TikTok</option>
          <option>YouTube</option>
          <option>Other</option>
        </select>
        <input
          type="email"
          value={creatorForm.email}
          placeholder="Email"
          onChange={(event) => setCreatorForm((prev) => ({ ...prev, email: event.target.value }))}
        />
        <button type="submit" className="primary-btn">
          Add creator
        </button>
      </form>
      <ul className="simple-list">
        {creators.map((creator) => (
          <li key={creator.id}>
            <strong>{creator.name}</strong>
            <span>{creator.handle}</span>
            <span>{creator.platform}</span>
          </li>
        ))}
      </ul>
    </article>
  )
}

export default CreatorsPage
