function CampaignsPage({ campaigns, campaignForm, setCampaignForm, onCreateCampaign }) {
  return (
    <article className="card mdx-surface mdx-prose form-card page-stack">
      <p className="mdx-kicker">Campaign Editor</p>
      <h3>2. Create campaign</h3>
      <div className="mdx-section-rule" />
      <p>Define campaign basics first, then move creators through workflow stages.</p>
      <form onSubmit={onCreateCampaign} className="inline-form page-form-grid">
        <input
          type="text"
          value={campaignForm.name}
          placeholder="Campaign name"
          onChange={(event) => setCampaignForm((prev) => ({ ...prev, name: event.target.value }))}
          required
        />
        <input
          type="number"
          value={campaignForm.budget}
          placeholder="Budget"
          onChange={(event) => setCampaignForm((prev) => ({ ...prev, budget: event.target.value }))}
        />
        <select
          value={campaignForm.status}
          onChange={(event) => setCampaignForm((prev) => ({ ...prev, status: event.target.value }))}
        >
          <option value="draft">Draft</option>
          <option value="active">Active</option>
          <option value="completed">Completed</option>
        </select>
        <button type="submit" className="primary-btn">
          Add campaign
        </button>
      </form>
      <ul className="simple-list">
        {campaigns.map((campaign) => (
          <li key={campaign.id}>
            <strong>{campaign.name}</strong>
            <span>{campaign.status}</span>
            <span>{campaign.budget ? `$${campaign.budget}` : 'Budget tbd'}</span>
          </li>
        ))}
      </ul>
    </article>
  )
}

export default CampaignsPage
