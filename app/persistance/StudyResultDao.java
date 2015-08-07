package persistance;

import java.util.List;

import javax.persistence.Query;
import javax.persistence.TypedQuery;

import models.GroupResult;
import models.StudyModel;
import models.StudyResult;
import models.workers.Worker;
import play.db.jpa.JPA;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * DAO for StudyResult model
 * 
 * @author Kristian Lange
 */
@Singleton
public class StudyResultDao extends AbstractDao {

	private final GroupResultDao groupResultDao;

	@Inject
	StudyResultDao(GroupResultDao groupResultDao) {
		this.groupResultDao = groupResultDao;
	}
	
	/**
	 * Creates StudyResult and adds it to the given Worker.
	 */
	public StudyResult create(StudyModel study, Worker worker) {
		StudyResult studyResult = new StudyResult(study);
		persist(studyResult);
		worker.addStudyResult(studyResult);
		merge(worker);
		return studyResult;
	}

	public void update(StudyResult studyResult) {
		merge(studyResult);
	}

	/**
	 * Remove all ComponentResults of the given StudyResult, remove StudyResult
	 * from the given worker, and remove StudyResult itself.
	 */
	public void remove(StudyResult studyResult) {
		// Remove all component results of this study result
		studyResult.getComponentResultList().forEach(this::remove);

		// Remove study result from worker
		Worker worker = studyResult.getWorker();
		worker.removeStudyResult(studyResult);
		merge(worker);

		// Remove studyResult from groupResult
		GroupResult groupResult = studyResult.getGroupResult();
		if (groupResult != null) {
			groupResult.removeStudyResult(studyResult);
			if (groupResult.getStudyResultList().isEmpty()) {
				// Remove groupResult if it has no StudyResults
				groupResultDao.remove(groupResult);
			} else {
				merge(groupResult);
			}
		}
		
		// Remove studyResult
		super.remove(studyResult);
	}
	
	public void refresh(StudyResult studyResult) {
		super.refresh(studyResult);
	}

	/**
	 * Removes ALL StudyResults including their ComponentResult of the specified
	 * study.
	 */
	public void removeAllOfStudy(StudyModel study) {
		List<StudyResult> studyResultList = findAllByStudy(study);
		studyResultList.forEach(this::remove);
	}

	public StudyResult findById(Long id) {
		return JPA.em().find(StudyResult.class, id);
	}

	/**
	 * Returns the number of StudyResults belonging to the given study.
	 */
	public int countByStudy(StudyModel study) {
		String queryStr = "SELECT COUNT(e) FROM StudyResult e WHERE e.study=:studyId";
		Query query = JPA.em().createQuery(queryStr);
		Number result = (Number) query.setParameter("studyId", study)
				.getSingleResult();
		return result.intValue();
	}

	public List<StudyResult> findAllByStudy(StudyModel study) {
		String queryStr = "SELECT e FROM StudyResult e "
				+ "WHERE e.study=:studyId";
		TypedQuery<StudyResult> query = JPA.em().createQuery(queryStr,
				StudyResult.class);
		return query.setParameter("studyId", study).getResultList();
	}

}
