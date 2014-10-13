package controllers;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import models.ComponentModel;
import models.StudyModel;
import models.UserModel;
import models.results.StudyResult;
import models.workers.MAWorker;
import play.Logger;
import play.data.DynamicForm;
import play.data.Form;
import play.db.jpa.Transactional;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Http.MultipartFormData;
import play.mvc.Http.MultipartFormData.FilePart;
import play.mvc.Result;
import play.mvc.Security;
import play.mvc.SimpleResult;
import services.ErrorMessages;
import services.IOUtils;
import services.JsonUtils;
import services.IOUtils.UploadUnmarshaller;
import services.PersistanceUtils;
import services.ZipUtil;
import controllers.publix.MAPublix;
import exceptions.ResultException;

@Security.Authenticated(Secured.class)
public class Studies extends Controller {

	private static final String CLASS_NAME = Studies.class.getSimpleName();

	@Transactional
	public static Result index(Long studyId, String errorMsg, int httpStatus)
			throws ResultException {
		Logger.info(CLASS_NAME + ".index: studyId " + studyId + ", "
				+ "logged-in user's email " + session(Users.COOKIE_EMAIL));
		StudyModel study = StudyModel.findById(studyId);
		UserModel loggedInUser = ControllerUtils.getLoggedInUser();
		List<StudyModel> studyList = StudyModel.findAll();
		ControllerUtils.checkStandardForStudy(study, studyId, loggedInUser,
				studyList);

		List<StudyResult> studyResultList = getStudyResultsNotDoneByMA(study);

		String breadcrumbs = Breadcrumbs.generateBreadcrumbs(
				Breadcrumbs.getHomeBreadcrumb(),
				Breadcrumbs.getStudyBreadcrumb(study));
		return status(httpStatus, views.html.mecharg.study.index.render(
				studyList, loggedInUser, breadcrumbs, errorMsg, study,
				studyResultList));
	}

	@Transactional
	public static Result index(Long studyId) throws ResultException {
		return index(studyId, null, Http.Status.OK);
	}

	private static List<StudyResult> getStudyResultsNotDoneByMA(StudyModel study) {
		List<StudyResult> studyResultList = StudyResult.findAllByStudy(study);
		Iterator<StudyResult> iter = studyResultList.iterator();
		while (iter.hasNext()) {
			if (iter.next().getWorker() instanceof MAWorker) {
				iter.remove();
			}
		}
		return studyResultList;
	}

	@Transactional
	public static Result create() throws ResultException {
		Logger.info(CLASS_NAME + ".create: " + "logged-in user's email "
				+ session(Users.COOKIE_EMAIL));
		List<StudyModel> studyList = StudyModel.findAll();
		UserModel loggedInUser = ControllerUtils.getLoggedInUser();

		String breadcrumbs = Breadcrumbs.generateBreadcrumbs(
				Breadcrumbs.getHomeBreadcrumb(), "New Study");
		return ok(views.html.mecharg.study.create.render(studyList,
				loggedInUser, breadcrumbs, Form.form(StudyModel.class)));
	}

	@Transactional
	public static Result submit() throws ResultException {
		Logger.info(CLASS_NAME + ".submit: " + "logged-in user's email "
				+ session(Users.COOKIE_EMAIL));
		Form<StudyModel> form = Form.form(StudyModel.class).bindFromRequest();
		UserModel loggedInUser = ControllerUtils.getLoggedInUser();
		List<StudyModel> studyList = StudyModel.findAll();
		if (form.hasErrors()) {
			String breadcrumbs = Breadcrumbs.generateBreadcrumbs(
					Breadcrumbs.getHomeBreadcrumb(), "New Study");
			SimpleResult result = badRequest(views.html.mecharg.study.create
					.render(studyList, loggedInUser, breadcrumbs, form));
			throw new ResultException(result);
		}

		// Persist in DB
		StudyModel study = form.get();
		PersistanceUtils.addStudy(study, loggedInUser);

		// Create study's dir
		try {
			IOUtils.createStudyDir(study);
		} catch (IOException e) {
			form.reject(e.getMessage());
			String breadcrumbs = Breadcrumbs.generateBreadcrumbs(
					Breadcrumbs.getHomeBreadcrumb(), "New Study");
			SimpleResult result = badRequest(views.html.mecharg.study.create
					.render(studyList, loggedInUser, breadcrumbs, form));
			throw new ResultException(result);
		}
		return redirect(routes.Studies.index(study.getId()));
	}

	@Transactional
	public static Result importStudy() throws ResultException {
		Logger.info(CLASS_NAME + ".importStudy: " + "logged-in user's email "
				+ session(Users.COOKIE_EMAIL));
		UserModel loggedInUser = ControllerUtils.getLoggedInUser();

		// Unzip uploaded file into a temp directory
		MultipartFormData mfd = request().body().asMultipartFormData();
		FilePart filePart = mfd.getFile(StudyModel.STUDY);
		File tempDir;
		try {
			tempDir = ZipUtil.unzip(filePart.getFile());
		} catch (IOException e1) {
			String errorMsg = ErrorMessages.IMPORT_OF_STUDY_FAILED;
			SimpleResult result = (SimpleResult) Home.home(errorMsg,
					Http.Status.INTERNAL_SERVER_ERROR);
			throw new ResultException(result, errorMsg);
		}

		// Unmarshal the study data and persist the new StudyModel
		File studyFile = IOUtils.findFiles(tempDir, "",
				IOUtils.STUDY_FILE_SUFFIX)[0];
		UploadUnmarshaller uploadUnmarshaller = new IOUtils.UploadUnmarshaller();
		StudyModel study = uploadUnmarshaller.unmarshalling(studyFile,
				StudyModel.class);
		if (study == null) {
			SimpleResult result = (SimpleResult) Home.home(
					uploadUnmarshaller.getErrorMsg(), Http.Status.BAD_REQUEST);
			throw new ResultException(result, uploadUnmarshaller.getErrorMsg());
		}
		if (study.validate() != null) {
			String errorMsg = ErrorMessages.COMPONENT_INVALID;
			SimpleResult result = (SimpleResult) Home.home(errorMsg,
					Http.Status.BAD_REQUEST);
			throw new ResultException(result, errorMsg);
		}
		PersistanceUtils.addStudy(study, loggedInUser);
		studyFile.delete();

		// Move and rename temporary study dir
		try {
			File studyDir = IOUtils.findFiles(tempDir,
					IOUtils.STUDY_DIR_PREFIX, "")[0];
			IOUtils.moveStudyDirectory(studyDir, study);
		} catch (IOException e) {
			String errorMsg = ErrorMessages.studysDirNotCreated(IOUtils
					.generateStudysPath(study.getId()));
			SimpleResult result = (SimpleResult) Home.home(errorMsg,
					Http.Status.INTERNAL_SERVER_ERROR);
			throw new ResultException(result, errorMsg);
		}

		return redirect(routes.Home.home());
	}

	@Transactional
	public static Result edit(Long studyId) throws ResultException {
		Logger.info(CLASS_NAME + ".edit: studyId " + studyId + ", "
				+ "logged-in user's email " + session(Users.COOKIE_EMAIL));
		StudyModel study = StudyModel.findById(studyId);
		UserModel loggedInUser = ControllerUtils.getLoggedInUser();
		List<StudyModel> studyList = StudyModel.findAll();
		ControllerUtils.checkStandardForStudy(study, studyId, loggedInUser,
				studyList);
		ControllerUtils.checkStudyLocked(study);

		Form<StudyModel> form = Form.form(StudyModel.class).fill(study);
		String breadcrumbs = Breadcrumbs.generateBreadcrumbs(
				Breadcrumbs.getHomeBreadcrumb(),
				Breadcrumbs.getStudyBreadcrumb(study), "Edit");
		return ok(views.html.mecharg.study.edit.render(studyList, loggedInUser,
				breadcrumbs, study, form));
	}

	@Transactional
	public static Result submitEdited(Long studyId) throws ResultException {
		Logger.info(CLASS_NAME + ".submitEdited: studyId " + studyId + ", "
				+ "logged-in user's email " + session(Users.COOKIE_EMAIL));
		StudyModel study = StudyModel.findById(studyId);
		UserModel loggedInUser = ControllerUtils.getLoggedInUser();
		List<StudyModel> studyList = StudyModel.findAll();
		ControllerUtils.checkStandardForStudy(study, studyId, loggedInUser,
				studyList);
		ControllerUtils.checkStudyLocked(study);

		Form<StudyModel> form = Form.form(StudyModel.class).bindFromRequest();
		if (form.hasErrors()) {
			String breadcrumbs = Breadcrumbs.generateBreadcrumbs(
					Breadcrumbs.getHomeBreadcrumb(),
					Breadcrumbs.getStudyBreadcrumb(study), "Edit");
			SimpleResult result = badRequest(views.html.mecharg.study.edit
					.render(studyList, loggedInUser, breadcrumbs, study, form));
			throw new ResultException(result);
		}

		// Update study in DB
		DynamicForm requestData = Form.form().bindFromRequest();
		String title = requestData.get(StudyModel.TITLE);
		String description = requestData.get(StudyModel.DESCRIPTION);
		String jsonData = requestData.get(StudyModel.JSON_DATA);
		PersistanceUtils.updateStudy(study, title, description, jsonData);
		return redirect(routes.Studies.index(studyId));
	}

	/**
	 * Ajax POST request to swap the locked field.
	 */
	@Transactional
	public static Result swapLock(Long studyId) throws ResultException {
		Logger.info(CLASS_NAME + ".swapLock: studyId " + studyId + ", "
				+ "logged-in user's email " + session(Users.COOKIE_EMAIL));
		StudyModel study = StudyModel.findById(studyId);
		UserModel loggedInUser = ControllerUtils.getLoggedInUserAjax();
		ControllerUtils.checkStandardForStudyAjax(study, studyId, loggedInUser);

		study.setLocked(!study.isLocked());
		study.merge();
		return ok(String.valueOf(study.isLocked()));
	}

	/**
	 * Ajax DELETE request to remove a study
	 */
	@Transactional
	public static Result remove(Long studyId) throws ResultException {
		Logger.info(CLASS_NAME + ".remove: studyId " + studyId + ", "
				+ "logged-in user's email " + session(Users.COOKIE_EMAIL));
		StudyModel study = StudyModel.findById(studyId);
		UserModel loggedInUser = ControllerUtils.getLoggedInUserAjax();
		ControllerUtils.checkStandardForStudyAjax(study, studyId, loggedInUser);
		ControllerUtils.checkStudyLockedAjax(study);

		PersistanceUtils.removeStudy(study);

		// Remove study's dir
		try {
			IOUtils.removeStudyDirectory(study);
		} catch (IOException e) {
			String errorMsg = e.getMessage();
			SimpleResult result = internalServerError(errorMsg);
			throw new ResultException(result, errorMsg);
		}
		return ok();
	}

	/**
	 * Ajax DELETE request to remove all study results including their component
	 * results.
	 */
	@Transactional
	public static Result removeAllResults(Long studyId) throws ResultException {
		Logger.info(CLASS_NAME + ".removeAllResults: studyId " + studyId + ", "
				+ "logged-in user's email " + session(Users.COOKIE_EMAIL));
		StudyModel study = StudyModel.findById(studyId);
		UserModel loggedInUser = ControllerUtils.getLoggedInUserAjax();
		ControllerUtils.checkStandardForStudyAjax(study, studyId, loggedInUser);
		ControllerUtils.checkStudyLockedAjax(study);

		PersistanceUtils.removeAllStudyResults(study);
		return ok();
	}

	@Transactional
	public static Result cloneStudy(Long studyId) throws ResultException {
		Logger.info(CLASS_NAME + ".cloneStudy: studyId " + studyId + ", "
				+ "logged-in user's email " + session(Users.COOKIE_EMAIL));
		StudyModel study = StudyModel.findById(studyId);
		UserModel loggedInUser = ControllerUtils.getLoggedInUser();
		List<StudyModel> studyList = StudyModel.findAll();
		ControllerUtils.checkStandardForStudy(study, studyId, loggedInUser,
				studyList);

		StudyModel clone = new StudyModel(study);
		clone.addMember(loggedInUser);
		clone.persist();

		// Copy study's dir and it's content to cloned study's dir
		try {
			IOUtils.copyStudyDirectory(study, clone);
		} catch (IOException e) {
			String errorMsg = e.getMessage();
			SimpleResult result = (SimpleResult) Studies.index(studyId,
					errorMsg, Http.Status.INTERNAL_SERVER_ERROR);
			throw new ResultException(result, errorMsg);
		}
		return redirect(routes.Studies.index(clone.getId()));
	}

	@Transactional
	public static Result exportStudy(Long studyId) throws ResultException {
		Logger.info(CLASS_NAME + ".exportStudy: studyId " + studyId + ", "
				+ "logged-in user's email " + session(Users.COOKIE_EMAIL));
		StudyModel study = StudyModel.findById(studyId);
		UserModel loggedInUser = ControllerUtils.getLoggedInUser();
		ControllerUtils.checkStandardForStudyAjax(study, studyId, loggedInUser);

		File zipFile;
		try {
			File studyAsJsonFile = File.createTempFile(
					IOUtils.generateFileName(study.getTitle()), "."
							+ IOUtils.STUDY_FILE_SUFFIX);
			JsonUtils.asJsonForIO(study, studyAsJsonFile);
			String studyDirPath = IOUtils.generateStudysPath(study.getId());
			zipFile = ZipUtil.zipStudy(studyDirPath,
					studyAsJsonFile.getAbsolutePath());
			studyAsJsonFile.delete();
		} catch (IOException e) {
			String errorMsg = ErrorMessages.studyExportFailure(studyId);
			SimpleResult result = internalServerError(errorMsg);
			throw new ResultException(result, errorMsg);
		}

		String zipFileName = IOUtils.generateFileName(study.getTitle(),
				IOUtils.ZIP_FILE_SUFFIX);
		response().setContentType("application/x-download");
		response().setHeader("Content-disposition",
				"attachment; filename=" + zipFileName);
		return ok(zipFile);
	}

	@Transactional
	public static Result changeMembers(Long studyId) throws ResultException {
		return changeMembers(studyId, null, Http.Status.OK);
	}

	@Transactional
	public static Result changeMembers(Long studyId, String errorMsg,
			int httpStatus) throws ResultException {
		Logger.info(CLASS_NAME + ".changeMembers: studyId " + studyId + ", "
				+ "logged-in user's email " + session(Users.COOKIE_EMAIL));
		StudyModel study = StudyModel.findById(studyId);
		UserModel loggedInUser = ControllerUtils.getLoggedInUser();
		List<StudyModel> studyList = StudyModel.findAll();
		ControllerUtils.checkStandardForStudy(study, studyId, loggedInUser,
				studyList);

		List<UserModel> userList = UserModel.findAll();
		String breadcrumbs = Breadcrumbs.generateBreadcrumbs(
				Breadcrumbs.getHomeBreadcrumb(),
				Breadcrumbs.getStudyBreadcrumb(study), "Change Members");
		return status(httpStatus,
				views.html.mecharg.study.changeMembers.render(studyList,
						loggedInUser, breadcrumbs, study, userList, errorMsg));
	}

	@Transactional
	public static Result submitChangedMembers(Long studyId)
			throws ResultException {
		Logger.info(CLASS_NAME + ".submitChangedMembers: studyId " + studyId
				+ ", " + "logged-in user's email "
				+ session(Users.COOKIE_EMAIL));
		StudyModel study = StudyModel.findById(studyId);
		UserModel loggedInUser = ControllerUtils.getLoggedInUser();
		List<StudyModel> studyList = StudyModel.findAll();
		ControllerUtils.checkStandardForStudy(study, studyId, loggedInUser,
				studyList);

		Map<String, String[]> formMap = request().body().asFormUrlEncoded();
		String[] checkedUsers = formMap.get(StudyModel.MEMBERS);
		if (checkedUsers == null || checkedUsers.length < 1) {
			String errorMsg = ErrorMessages.STUDY_AT_LEAST_ONE_MEMBER;
			SimpleResult result = (SimpleResult) changeMembers(studyId,
					errorMsg, Http.Status.BAD_REQUEST);
			throw new ResultException(result, errorMsg);
		}
		study.getMemberList().clear();
		for (String email : checkedUsers) {
			UserModel user = UserModel.findByEmail(email);
			if (user != null) {
				PersistanceUtils.addMemberToStudy(study, user);
			}
		}

		return redirect(routes.Studies.index(studyId));
	}

	/**
	 * Ajax POST request to change the oder of components within an study.
	 */
	@Transactional
	public static Result changeComponentOrder(Long studyId, Long componentId,
			String direction) throws ResultException {
		Logger.info(CLASS_NAME + ".changeComponentOrder: studyId " + studyId
				+ ", " + "logged-in user's email "
				+ session(Users.COOKIE_EMAIL));
		StudyModel study = StudyModel.findById(studyId);
		UserModel loggedInUser = ControllerUtils.getLoggedInUserAjax();
		ComponentModel component = ComponentModel.findById(componentId);
		ControllerUtils.checkStandardForStudyAjax(study, studyId, loggedInUser);
		ControllerUtils.checkStudyLockedAjax(study);
		ControllerUtils.checkStandardForComponentsAjax(studyId, componentId,
				study, loggedInUser, component);

		if (direction.equals("up")) {
			study.componentOrderMinusOne(component);
		}
		if (direction.equals("down")) {
			study.componentOrderPlusOne(component);
		}
		study.refresh();

		return ok();
	}

	@Transactional
	public static Result tryStudy(Long studyId) throws ResultException {
		Logger.info(CLASS_NAME + ".tryStudy: studyId " + studyId + ", "
				+ "logged-in user's email " + session(Users.COOKIE_EMAIL));
		StudyModel study = StudyModel.findById(studyId);
		UserModel loggedInUser = ControllerUtils.getLoggedInUser();
		List<StudyModel> studyList = StudyModel.findAll();
		ControllerUtils.checkStandardForStudy(study, studyId, loggedInUser,
				studyList);
		ControllerUtils.checkStudyLocked(study);

		session(MAPublix.MECHARG_TRY, StudyModel.STUDY);
		return redirect(controllers.publix.routes.PublixInterceptor
				.startStudy(study.getId()));
	}

	@Transactional
	public static Result showMTurkSourceCode(Long studyId)
			throws ResultException {
		Logger.info(CLASS_NAME + ".showMTurkSourceCode: studyId " + studyId
				+ ", " + "logged-in user's email "
				+ session(Users.COOKIE_EMAIL));
		StudyModel study = StudyModel.findById(studyId);
		UserModel loggedInUser = ControllerUtils.getLoggedInUser();
		List<StudyModel> studyList = StudyModel.findAll();
		ControllerUtils.checkStandardForStudy(study, studyId, loggedInUser,
				studyList);

		String hostname = request().host();
		String breadcrumbs = Breadcrumbs.generateBreadcrumbs(
				Breadcrumbs.getHomeBreadcrumb(),
				Breadcrumbs.getStudyBreadcrumb(study),
				"Mechanical Turk HIT layout source code");
		return ok(views.html.mecharg.study.mTurkSourceCode.render(studyList,
				loggedInUser, breadcrumbs, null, study, hostname));
	}

}
